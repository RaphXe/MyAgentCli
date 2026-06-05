package com.raph.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raph.llm.LlmClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    private static final ObjectMapper mapper = new ObjectMapper();
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final long TOOL_TIMEOUT_SECONDS = 90;
    private static final AtomicLong TOOL_CALLING_SEQUENCE = new AtomicLong();
    private static final ConcurrentHashMap<Path, FileMutationOwner> ACTIVE_FILE_MUTATIONS = new ConcurrentHashMap<>();

    public ToolRegistry() {
        registerFileTools();
        registerShellTools();
        registerProjectTools();
    }

    private void registerFileTools () {
        // read_file 工具
        tools.put("read_file", new Tool(
                "read_file",
                "读取文件内容，用于查看代码、配置文件等",
                createParameters(new Param("path", "string", "文件路径", true)),
                ToolMetadata.readOnly(),
                args -> {
                    String path = args.get("path");
                    try {
                        String content = Files.readString(Path.of(path));
                        return "文件内容:\n" + content;
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        // write_file 工具
        tools.put("write_file", new Tool(
                "write_file",
                "写入文件内容；mode=overwrite 覆盖写入，mode=append 追加写入",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true),
                        new Param("mode", "string", "写入模式：overwrite 覆盖写入（默认），append 追加写入", false)
                ),
                ToolMetadata.fileMutation("path"),
                args -> {
                    String path = args.get("path");
                    String content = args.getOrDefault("content", "");
                    String mode = args.getOrDefault("mode", "overwrite");
                    try {
                        if ("append".equalsIgnoreCase(mode)) {
                            Files.writeString(Path.of(path), content,
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            return "文件已追加写入: " + path;
                        }
                        Files.writeString(Path.of(path), content);
                        return "文件已覆盖写入: " + path;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        ));

        // list_dir 工具
        tools.put("list_dir", new Tool(
                "list_dir",
                "列出目录内容，用于查看项目结构、查找文件等",
                createParameters(new Param("path", "string", "目录路径", true)),
                ToolMetadata.readOnly(),
                args -> {
                    String path = args.get("path");
                    try {
                        StringBuilder sb = new StringBuilder();
                        try (var stream = Files.list(Path.of(path))) {
                            stream.sorted().forEach(entry -> {
                                String name = entry.getFileName().toString();
                                if (Files.isDirectory(entry)) {
                                    sb.append(name).append("/\n");
                                } else {
                                    sb.append(name).append("\n");
                                }
                            });
                        }
                        return "目录内容:\n" + sb;
                    } catch (Exception e) {
                        return "列出目录失败: " + e.getMessage();
                    }
                }
        ));

    }

    private void registerShellTools () {
        tools.put("execute_command", new Tool(
                "execute_command",
                "执行Shell命令，用于编译、运行、Git操作等",
                createParameters(new Param("command", "string", "要执行的命令", true)),
                ToolMetadata.readOnly(),
                args -> {
                    String command = args.get("command");
                    try {
                        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                        pb.redirectErrorStream(true);
                        Process process = pb.start();

                        boolean finished = process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (!finished) {
                            process.destroyForcibly();
                            process.waitFor(1, TimeUnit.SECONDS);
                            return "命令执行超时: 超过 " + TOOL_TIMEOUT_SECONDS + " 秒\n" + readProcessOutput(process);
                        }
                        int exitCode = process.exitValue();
                        return String.format("命令执行完成 (exit code: %d)\n%s",
                                exitCode, readProcessOutput(process));
                    } catch (Exception e) {
                        return "执行命令失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册项目脚手架工具
     */
    private void registerProjectTools() {
        // create_project 工具
        tools.put("create_project", new Tool(
                "create_project",
                "创建新项目结构，自动生成基础目录和 README 文件",
                createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("type", "string", "项目类型 (java/python/general)，默认 general", false)
                ),
                ToolMetadata.fileMutation("name"),
                args -> {
                    String name = args.get("name");
                    String type = args.getOrDefault("type", "general");
                    try {
                        Path projectDir = Path.of(name);
                        Files.createDirectories(projectDir);

                        // 根据项目类型创建不同的目录结构
                        switch (type.toLowerCase()) {
                            case "java":
                                Files.createDirectories(projectDir.resolve("src/main/java"));
                                Files.createDirectories(projectDir.resolve("src/test/java"));
                                break;
                            case "python":
                                Files.createDirectories(projectDir.resolve("src"));
                                Files.createDirectories(projectDir.resolve("tests"));
                                break;
                            default:
                                // general 类型：只创建 src 目录
                                Files.createDirectories(projectDir.resolve("src"));
                                break;
                        }

                        // 创建 README.md 骨架文件
                        String readme = "# " + name + "\n\n项目描述\n";
                        Files.writeString(projectDir.resolve("README.md"), readme);

                        return "项目已创建: " + name + " (类型: " + type + ")";
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 创建参数定义
     */
    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    public List<LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    public String executeTool(String toolName, String arguments) {
        return executeTool(toolName, arguments, TOOL_CALLING_SEQUENCE.incrementAndGet(), false);
    }

    protected String executeTool(String toolName, String arguments, long toolCallingId, boolean batchReservationHeld) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "工具不存在: " + toolName;
        }

        Map<String, String> args;
        try {
            args = parseArguments(arguments);
        } catch (Exception e) {
            return "工具参数解析失败: " + e.getMessage();
        }

        FileMutationLease lease = FileMutationLease.none();
        try {
            lease = acquireFileMutationLease(tool, args, toolCallingId, batchReservationHeld);
            if (lease.errorMessage() != null) {
                return lease.errorMessage();
            }
            return tool.executor().execute(args);
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        } finally {
            releaseFileMutationLease(lease);
        }
    }

    public List<ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        long toolCallingId = TOOL_CALLING_SEQUENCE.incrementAndGet();
        BatchMutationReservation reservation = reserveBatchMutations(toolCalls, toolCallingId);
        if (reservation.errorMessage() != null) {
            List<ToolExecutionResult> results = new ArrayList<>(toolCalls.size());
            for (LlmClient.ToolCall toolCall : toolCalls) {
                results.add(failureResult(toolCall, reservation.errorMessage()));
            }
            return List.copyOf(results);
        }

        try {
            if (toolCalls.size() == 1 || reservation.hasInternalConflict()) {
                List<ToolExecutionResult> results = new ArrayList<>(toolCalls.size());
                for (LlmClient.ToolCall toolCall : toolCalls) {
                    results.add(executeToolCall(toolCall, toolCallingId, true));
                }
                return List.copyOf(results);
            }

            return executeToolsConcurrently(toolCalls, toolCallingId);
        } finally {
            releaseBatchReservation(reservation, toolCallingId);
        }
    }

    private List<ToolExecutionResult> executeToolsConcurrently(List<LlmClient.ToolCall> toolCalls, long toolCallingId) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL_TOOLS, toolCalls.size()));
        CompletionService<ToolExecutionResult> completionService = new ExecutorCompletionService<>(executor);
        Map<Future<ToolExecutionResult>, LlmClient.ToolCall> submittedCalls = new HashMap<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            Future<ToolExecutionResult> future = completionService.submit(() -> executeToolCall(toolCall, toolCallingId, true));
            submittedCalls.put(future, toolCall);
        }

        try {
            List<ToolExecutionResult> results = new ArrayList<>(toolCalls.size());
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TOOL_TIMEOUT_SECONDS);
            int completedCount = 0;
            while (completedCount < toolCalls.size()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                Future<ToolExecutionResult> future = completionService.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (future == null) {
                    break;
                }
                completedCount++;
                LlmClient.ToolCall toolCall = submittedCalls.remove(future);
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    results.add(failureResult(toolCall, "工具执行失败: " + e.getMessage()));
                }
            }

            for (Map.Entry<Future<ToolExecutionResult>, LlmClient.ToolCall> pending : submittedCalls.entrySet()) {
                pending.getKey().cancel(true);
                results.add(timeoutResult(pending.getValue()));
            }
            return List.copyOf(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            List<ToolExecutionResult> results = new ArrayList<>(toolCalls.size());
            for (LlmClient.ToolCall toolCall : toolCalls) {
                results.add(failureResult(toolCall, "工具执行被中断: " + e.getMessage()));
            }
            return List.copyOf(results);
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolExecutionResult executeToolCall(LlmClient.ToolCall toolCall, long toolCallingId, boolean batchReservationHeld) {
        String toolName = toolName(toolCall);
        String arguments = toolCall == null || toolCall.function() == null ? null : toolCall.function().arguments();
        return new ToolExecutionResult(
                toolCall == null ? null : toolCall.id(),
                toolName,
                executeTool(toolName, arguments, toolCallingId, batchReservationHeld)
        );
    }

    private BatchMutationReservation reserveBatchMutations(List<LlmClient.ToolCall> toolCalls, long toolCallingId) {
        List<Path> mutationPaths = new ArrayList<>();
        boolean hasInternalConflict = false;
        for (LlmClient.ToolCall toolCall : toolCalls) {
            Path path = mutationPath(toolCall);
            if (path == null) {
                continue;
            }
            for (Path existingPath : mutationPaths) {
                if (pathsConflict(path, existingPath)) {
                    hasInternalConflict = true;
                    break;
                }
            }
            addUniquePath(mutationPaths, path);
        }

        if (mutationPaths.isEmpty()) {
            return new BatchMutationReservation(false, List.of(), null);
        }

        FileMutationOwner owner = new FileMutationOwner(toolCallingId, Thread.currentThread().getName());
        synchronized (ACTIVE_FILE_MUTATIONS) {
            for (Path path : mutationPaths) {
                for (Map.Entry<Path, FileMutationOwner> active : ACTIVE_FILE_MUTATIONS.entrySet()) {
                    if (pathsConflict(path, active.getKey()) && active.getValue().toolCallingId() != toolCallingId) {
                        return new BatchMutationReservation(
                                hasInternalConflict,
                                List.of(),
                                fileConflictMessage(path)
                        );
                    }
                }
            }
            for (Path path : mutationPaths) {
                ACTIVE_FILE_MUTATIONS.put(path, owner);
            }
        }
        return new BatchMutationReservation(hasInternalConflict, List.copyOf(mutationPaths), null);
    }

    private Path mutationPath(LlmClient.ToolCall toolCall) {
        String toolName = toolName(toolCall);
        String arguments = toolCall == null || toolCall.function() == null ? null : toolCall.function().arguments();
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return null;
        }
        try {
            return mutationPath(tool, parseArguments(arguments));
        } catch (Exception e) {
            return null;
        }
    }

    private Path mutationPath(Tool tool, Map<String, String> args) {
        ToolMetadata metadata = tool.metadata();
        if (metadata == null || !metadata.mutatesFile()) {
            return null;
        }
        String pathValue = args.get(metadata.pathArgument());
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }
        return Path.of(pathValue).toAbsolutePath().normalize();
    }

    private static void addUniquePath(List<Path> paths, Path path) {
        for (Path existingPath : paths) {
            if (existingPath.equals(path)) {
                return;
            }
        }
        paths.add(path);
    }

    private FileMutationLease acquireFileMutationLease(Tool tool, Map<String, String> args,
                                                       long toolCallingId, boolean batchReservationHeld) {
        Path path = mutationPath(tool, args);
        if (path == null) {
            return FileMutationLease.none();
        }

        synchronized (ACTIVE_FILE_MUTATIONS) {
            for (Map.Entry<Path, FileMutationOwner> active : ACTIVE_FILE_MUTATIONS.entrySet()) {
                if (!pathsConflict(path, active.getKey())) {
                    continue;
                }
                if (active.getValue().toolCallingId() == toolCallingId && batchReservationHeld) {
                    return FileMutationLease.none();
                }
                return FileMutationLease.error(fileConflictMessage(path));
            }
            ACTIVE_FILE_MUTATIONS.put(path, new FileMutationOwner(toolCallingId, Thread.currentThread().getName()));
            return FileMutationLease.acquired(path);
        }
    }

    private void releaseFileMutationLease(FileMutationLease lease) {
        if (lease.acquiredPath() != null) {
            synchronized (ACTIVE_FILE_MUTATIONS) {
                ACTIVE_FILE_MUTATIONS.remove(lease.acquiredPath());
            }
        }
    }

    private void releaseBatchReservation(BatchMutationReservation reservation, long toolCallingId) {
        synchronized (ACTIVE_FILE_MUTATIONS) {
            for (Path path : reservation.reservedPaths()) {
                FileMutationOwner owner = ACTIVE_FILE_MUTATIONS.get(path);
                if (owner != null && owner.toolCallingId() == toolCallingId) {
                    ACTIVE_FILE_MUTATIONS.remove(path);
                }
            }
        }
    }

    private static String fileConflictMessage(Path path) {
        return "文件修改冲突: " + path + " 正在被另一个工具调用批次修改，请稍后重试或重新规划。";
    }

    private static boolean pathsConflict(Path left, Path right) {
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    private static ToolExecutionResult timeoutResult(LlmClient.ToolCall toolCall) {
        return failureResult(toolCall, "工具执行超时: 超过 " + TOOL_TIMEOUT_SECONDS + " 秒");
    }

    private static ToolExecutionResult failureResult(LlmClient.ToolCall toolCall, String message) {
        return new ToolExecutionResult(toolCall == null ? null : toolCall.id(), toolName(toolCall), message);
    }

    private static String toolName(LlmClient.ToolCall toolCall) {
        if (toolCall == null || toolCall.function() == null) {
            return "";
        }
        return Objects.toString(toolCall.function().name(), "");
    }

    private static String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString();
    }

    private Map<String, String> parseArguments(String arguments) throws IOException {
        Map<String, String> args = new HashMap<>();
        if (arguments == null || arguments.isBlank()) {
            return args;
        }

        JsonNode root = mapper.readTree(arguments);
        if (!root.isObject()) {
            throw new IOException("工具参数必须是 JSON 对象");
        }

        root.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                args.put(entry.getKey(), null);
            } else if (value.isValueNode()) {
                args.put(entry.getKey(), value.asText());
            } else {
                args.put(entry.getKey(), value.toString());
            }
        });
        return args;
    }

    public record Tool(String name, String description, JsonNode parameters, ToolMetadata metadata,
                       ToolExecutor executor) {}

    public record ToolMetadata(boolean mutatesFile, String pathArgument) {
        public static ToolMetadata readOnly() {
            return new ToolMetadata(false, null);
        }

        public static ToolMetadata fileMutation(String pathArgument) {
            return new ToolMetadata(true, pathArgument);
        }
    }

    public record ToolExecutionResult(String toolCallId, String toolName, String result) {}

    private record FileMutationOwner(long toolCallingId, String owner) {}

    private record FileMutationLease(Path acquiredPath, String errorMessage) {
        private static FileMutationLease none() {
            return new FileMutationLease(null, null);
        }

        private static FileMutationLease acquired(Path path) {
            return new FileMutationLease(path, null);
        }

        private static FileMutationLease error(String message) {
            return new FileMutationLease(null, message);
        }
    }

    private record BatchMutationReservation(boolean hasInternalConflict, List<Path> reservedPaths, String errorMessage) {}

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }

    private record Param(String name, String type, String description, boolean required) {}
}
