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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final WorkspacePolicy workspacePolicy;

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_READ_FILE_BYTES = 2 * 1024 * 1024;
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 64 * 1024;
    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final long TOOL_TIMEOUT_SECONDS = 90;
    private static final AtomicLong TOOL_CALLING_SEQUENCE = new AtomicLong();
    private static final ConcurrentHashMap<Path, FileMutationOwner> ACTIVE_FILE_MUTATIONS = new ConcurrentHashMap<>();

    public ToolRegistry() {
        this(WorkspacePolicy.defaultPolicy());
    }

    public ToolRegistry(WorkspacePolicy workspacePolicy) {
        this.workspacePolicy = workspacePolicy == null ? WorkspacePolicy.defaultPolicy() : workspacePolicy;
        registerFileTools();
        registerNavigationTools();
        registerShellTools();
        registerProjectTools();
    }

    public synchronized void registerTool(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            return;
        }
        tools.put(tool.name(), tool);
    }

    public synchronized boolean hasTool(String name) {
        return name != null && tools.containsKey(name);
    }

    public synchronized void unregisterTool(String name) {
        if (name != null && !name.isBlank()) {
            tools.remove(name);
        }
    }

    private void registerFileTools () {
        // read_file 工具
        tools.put("read_file", new Tool(
                "read_file",
                "读取文件内容，用于查看代码、配置文件等",
                createParameters(new Param("path", "string", "文件路径", true)),
                ToolMetadata.readOnly(),
                args -> {
                    Path path = workspacePolicy.resolve(args.get("path"));
                    try {
                        if (Files.exists(path) && Files.size(path) > MAX_READ_FILE_BYTES) {
                            return "读取文件失败: 文件超过读取上限 " + MAX_READ_FILE_BYTES + " bytes: " + path;
                        }
                        String content = Files.readString(path);
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
                    Path path = workspacePolicy.resolve(args.get("path"));
                    String content = args.getOrDefault("content", "");
                    String mode = args.getOrDefault("mode", "overwrite");
                    try {
                        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE_FILE_BYTES) {
                            return "写入文件失败: 内容超过写入上限 " + MAX_WRITE_FILE_BYTES + " bytes";
                        }
                        Path parent = path.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        if ("append".equalsIgnoreCase(mode)) {
                            Files.writeString(path, content,
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            return "文件已追加写入: " + path;
                        }
                        writeStringAtomically(path, content);
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
                    Path path = workspacePolicy.resolve(args.get("path"));
                    try {
                        StringBuilder sb = new StringBuilder();
                        try (var stream = Files.list(path)) {
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

    private void registerNavigationTools() {
        tools.put("project_tree", new Tool(
                "project_tree",
                "生成项目目录树，用于快速了解项目结构；可设置 path、max_depth、include_files",
                createParameters(
                        new Param("path", "string", "起始目录，默认当前目录", false),
                        new Param("max_depth", "string", "最大深度，默认3，最大6", false),
                        new Param("include_files", "string", "是否包含文件，true/false，默认true", false)
                ),
                ToolMetadata.readOnly(),
                args -> {
                    Path root = workspacePolicy.resolve(args.getOrDefault("path", "."));
                    int maxDepth = clamp(parseInt(args.get("max_depth"), 3), 1, 6);
                    boolean includeFiles = parseBoolean(args.get("include_files"), true);
                    int maxEntries = 300;
                    try {
                        if (!Files.exists(root)) {
                            return "目录不存在: " + root;
                        }
                        List<Path> entries;
                        try (var stream = Files.walk(root, maxDepth)) {
                            entries = stream
                                    .filter(path -> !path.equals(root))
                                    .filter(path -> !isIgnoredPath(root, path))
                                    .filter(path -> includeFiles || Files.isDirectory(path))
                                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                                    .limit(maxEntries + 1L)
                                    .toList();
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("项目树: ").append(root).append(" (max_depth=").append(maxDepth).append(")\n");
                        int count = 0;
                        for (Path entry : entries) {
                            if (count >= maxEntries) {
                                sb.append("...（已截断，最多 ").append(maxEntries).append(" 项）\n");
                                break;
                            }
                            Path relative = root.relativize(entry);
                            int depth = relative.getNameCount();
                            sb.append("  ".repeat(Math.max(0, depth - 1)))
                                    .append(Files.isDirectory(entry) ? "📁 " : "📄 ")
                                    .append(relative.getFileName())
                                    .append(Files.isDirectory(entry) ? "/" : "")
                                    .append("\n");
                            count++;
                        }
                        if (count == 0) {
                            sb.append("(空目录或无匹配项)\n");
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return "生成项目树失败: " + e.getMessage();
                    }
                }
        ));

        tools.put("search_files", new Tool(
                "search_files",
                "按文件名或相对路径关键词搜索文件，用于快速定位入口文件、配置和文档",
                createParameters(
                        new Param("path", "string", "搜索目录，默认当前目录", false),
                        new Param("query", "string", "文件名或路径关键词，例如 pom、AgentRuntime、README", true),
                        new Param("max_results", "string", "最大结果数，默认50，最大100", false)
                ),
                ToolMetadata.readOnly(),
                args -> {
                    Path root = workspacePolicy.resolve(args.getOrDefault("path", "."));
                    String query = args.getOrDefault("query", "").trim().toLowerCase();
                    int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, 100);
                    if (query.isEmpty()) {
                        return "搜索失败: query 不能为空";
                    }
                    try {
                        if (!Files.exists(root)) {
                            return "目录不存在: " + root;
                        }
                        List<Path> matches;
                        try (var stream = Files.walk(root, 8)) {
                            matches = stream
                                    .filter(Files::isRegularFile)
                                    .filter(path -> !isIgnoredPath(root, path))
                                    .filter(path -> root.relativize(path).toString().toLowerCase().contains(query))
                                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                                    .limit(maxResults + 1L)
                                    .toList();
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("搜索结果 query=").append(query).append(" root=").append(root).append("\n");
                        int count = 0;
                        for (Path match : matches) {
                            if (count >= maxResults) {
                                sb.append("...（已截断，最多 ").append(maxResults).append(" 项）\n");
                                break;
                            }
                            sb.append("- ").append(root.relativize(match)).append("\n");
                            count++;
                        }
                        if (count == 0) {
                            sb.append("(无匹配文件)\n");
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return "搜索文件失败: " + e.getMessage();
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
                        pb.directory(workspacePolicy.primaryRoot().toFile());
                        pb.redirectErrorStream(true);
                        Process process = pb.start();
                        StringBuilder commandOutput = new StringBuilder();
                        Thread outputReader = startProcessOutputReader(process, commandOutput);

                        boolean finished = process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (!finished) {
                            process.destroyForcibly();
                            process.waitFor(1, TimeUnit.SECONDS);
                            joinQuietly(outputReader);
                            return "命令执行超时: 超过 " + TOOL_TIMEOUT_SECONDS + " 秒\n" + commandOutput;
                        }
                        joinQuietly(outputReader);
                        int exitCode = process.exitValue();
                        return String.format("命令执行完成 (exit code: %d)\n%s",
                                exitCode, commandOutput);
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
                    Path projectDir = workspacePolicy.resolve(args.get("name"));
                    String name = projectDir.getFileName() == null ? projectDir.toString() : projectDir.getFileName().toString();
                    String type = args.getOrDefault("type", "general");
                    try {
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

                        return "项目已创建: " + projectDir + " (类型: " + type + ")";
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 创建参数定义
     */
    private static boolean isIgnoredPath(Path root, Path path) {
        Path relative;
        try {
            relative = root.relativize(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(".git") || name.equals("target") || name.equals(".idea")
                    || name.equals(".codex") || name.equals(".agents")) {
                return true;
            }
        }
        return false;
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "no".equals(normalized) || "0".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

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

    public List<LlmClient.Tool> getToolDefinitions(Collection<String> allowedToolNames) {
        Set<String> allowed = normalizeAllowedToolNames(allowedToolNames);
        if (allowed.isEmpty()) {
            return List.of();
        }
        return tools.values().stream()
                .filter(t -> allowed.contains(t.name()))
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

        ParsedArguments args;
        try {
            args = parseArguments(arguments);
        } catch (Exception e) {
            return "工具参数解析失败: " + e.getMessage();
        }

        FileMutationLease lease = FileMutationLease.none();
        try {
            WorkspaceAccess workspaceAccess = workspaceAccess(toolName, args.stringArgs());
            if (workspaceAccess.requiresApproval()
                    && !workspacePolicy.isAllowedOrConsumeOneTime(workspaceAccess.targetPath())) {
                return workspaceDeniedMessage(workspaceAccess);
            }
            lease = acquireFileMutationLease(tool, args.stringArgs(), toolCallingId, batchReservationHeld);
            if (lease.errorMessage() != null) {
                return lease.errorMessage();
            }
            if (tool.jsonExecutor() != null) {
                return tool.jsonExecutor().execute(args.jsonArgs());
            }
            return tool.executor().execute(args.stringArgs());
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        } finally {
            releaseFileMutationLease(lease);
        }
    }

    public List<ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls) {
        return executeTools(toolCalls, null);
    }

    public List<ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls, Collection<String> allowedToolNames) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = normalizeAllowedToolNames(allowedToolNames);
        if (!allowed.isEmpty()) {
            for (LlmClient.ToolCall toolCall : toolCalls) {
                if (!allowed.contains(toolName(toolCall))) {
                    List<ToolExecutionResult> results = new ArrayList<>(toolCalls.size());
                    for (LlmClient.ToolCall call : toolCalls) {
                        results.add(failureResult(call, "工具不在当前子Agent允许范围内: " + toolName(call)));
                    }
                    return List.copyOf(results);
                }
            }
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

    private Set<String> normalizeAllowedToolNames(Collection<String> allowedToolNames) {
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return Set.of();
        }
        return allowedToolNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .filter(tools::containsKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
            return mutationPath(tool, parseArguments(arguments).stringArgs());
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
        return workspacePolicy.resolve(pathValue);
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

    protected WorkspaceAccess workspaceAccess(String toolName, Map<String, String> args) {
        if ("execute_command".equals(toolName)) {
            return commandWorkspaceAccess(args == null ? null : args.get("command"));
        }
        WorkspaceTarget target = workspaceTarget(toolName, args);
        if (target == null) {
            return WorkspaceAccess.allowed();
        }
        Path resolved = workspacePolicy.resolve(target.rawPath());
        if (workspacePolicy.isInsideWorkspace(resolved)) {
            return WorkspaceAccess.allowed(resolved);
        }
        return WorkspaceAccess.requiresApproval(
                resolved,
                workspacePolicy.suggestedRoot(resolved, target.directoryIntent()),
                "目标路径不在当前工作区内"
        );
    }

    private WorkspaceAccess commandWorkspaceAccess(String command) {
        for (String rawPath : absolutePathsInCommand(command)) {
            Path resolved = workspacePolicy.resolve(rawPath);
            if (!workspacePolicy.isInsideWorkspace(resolved)) {
                return WorkspaceAccess.requiresApproval(
                        resolved,
                        workspacePolicy.suggestedRoot(resolved, Files.isDirectory(resolved)),
                        "命令引用了当前工作区外的绝对路径"
                );
            }
        }
        return WorkspaceAccess.allowed();
    }

    protected WorkspacePolicy workspacePolicy() {
        return workspacePolicy;
    }

    public void clearSessionState() {
        workspacePolicy.resetSessionState();
    }

    protected ParsedArguments parseArguments(String arguments) throws IOException {
        Map<String, String> stringArgs = new HashMap<>();
        Map<String, JsonNode> jsonArgs = new HashMap<>();
        if (arguments == null || arguments.isBlank()) {
            return new ParsedArguments(stringArgs, jsonArgs);
        }

        JsonNode root = mapper.readTree(arguments);
        if (!root.isObject()) {
            throw new IOException("工具参数必须是 JSON 对象");
        }

        root.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                stringArgs.put(entry.getKey(), null);
                jsonArgs.put(entry.getKey(), mapper.nullNode());
            } else if (value.isValueNode()) {
                stringArgs.put(entry.getKey(), value.asText());
                jsonArgs.put(entry.getKey(), value);
            } else {
                stringArgs.put(entry.getKey(), value.toString());
                jsonArgs.put(entry.getKey(), value);
            }
        });
        return new ParsedArguments(stringArgs, jsonArgs);
    }

    private WorkspaceTarget workspaceTarget(String toolName, Map<String, String> args) {
        Map<String, String> safeArgs = args == null ? Map.of() : args;
        return switch (toolName == null ? "" : toolName) {
            case "read_file", "write_file" -> new WorkspaceTarget(safeArgs.get("path"), false);
            case "list_dir", "project_tree", "search_files" ->
                    new WorkspaceTarget(safeArgs.getOrDefault("path", "."), true);
            case "create_project" -> new WorkspaceTarget(safeArgs.get("name"), true);
            default -> null;
        };
    }

    private static List<String> absolutePathsInCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote && isShellDelimiter(ch)) {
                addAbsolutePath(paths, current);
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        addAbsolutePath(paths, current);
        return List.copyOf(paths);
    }

    private static boolean isShellDelimiter(char ch) {
        return Character.isWhitespace(ch) || ch == ';' || ch == '&' || ch == '|'
                || ch == '<' || ch == '>' || ch == '(' || ch == ')';
    }

    private static void addAbsolutePath(List<String> paths, StringBuilder token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String value = token.toString().trim();
        if (!value.startsWith("/")) {
            return;
        }
        while (value.length() > 1 && (value.endsWith(",") || value.endsWith(":"))) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.isBlank()) {
            paths.add(value);
        }
    }

    private static String workspaceDeniedMessage(WorkspaceAccess access) {
        return "工作区访问被拒绝: " + access.targetPath()
                + " 不在当前工作区内。请请求用户批准本次访问或扩展工作区到: "
                + access.suggestedRoot();
    }

    private static void writeStringAtomically(Path path, String content) throws IOException {
        Path parent = path.getParent();
        String prefix = path.getFileName() == null ? "paicli" : path.getFileName().toString();
        if (prefix.length() < 3) {
            prefix = (prefix + "___").substring(0, 3);
        }
        Path temp = Files.createTempFile(parent == null ? Path.of(".") : parent,
                prefix, ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
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

    private static Thread startProcessOutputReader(Process process, StringBuilder output) {
        Thread thread = new Thread(() -> {
            try {
                readProcessOutput(process, output);
            } catch (IOException e) {
                output.append("\n读取命令输出失败: ").append(e.getMessage()).append("\n");
            }
        }, "tool-command-output-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void readProcessOutput(Process process, StringBuilder output) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
                    output.append("\n...（输出已截断，最多 ")
                            .append(MAX_COMMAND_OUTPUT_CHARS)
                            .append(" 字符）\n");
                    break;
                }
                output.append(line).append("\n");
            }
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Tool(String name, String description, JsonNode parameters, ToolMetadata metadata,
                       ToolExecutor executor, JsonToolExecutor jsonExecutor) {
        public Tool(String name, String description, JsonNode parameters, ToolMetadata metadata,
                    ToolExecutor executor) {
            this(name, description, parameters, metadata, executor, null);
        }

        public static Tool json(String name, String description, JsonNode parameters, ToolMetadata metadata,
                                JsonToolExecutor executor) {
            return new Tool(name, description, parameters, metadata, null, executor);
        }
    }

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

    public interface JsonToolExecutor {
        String execute(Map<String, JsonNode> args);
    }

    public record WorkspaceAccess(boolean requiresApproval, Path targetPath, Path suggestedRoot, String reason) {
        private static WorkspaceAccess allowed() {
            return new WorkspaceAccess(false, null, null, null);
        }

        private static WorkspaceAccess allowed(Path targetPath) {
            return new WorkspaceAccess(false, targetPath, null, null);
        }

        private static WorkspaceAccess requiresApproval(Path targetPath, Path suggestedRoot, String reason) {
            return new WorkspaceAccess(true, targetPath, suggestedRoot, reason);
        }
    }

    protected record ParsedArguments(Map<String, String> stringArgs, Map<String, JsonNode> jsonArgs) {}

    private record WorkspaceTarget(String rawPath, boolean directoryIntent) {}

    private record Param(String name, String type, String description, boolean required) {}
}
