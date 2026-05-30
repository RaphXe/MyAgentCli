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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    private static final ObjectMapper mapper = new ObjectMapper();
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;

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
                "写入文件内容",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content");
                    try {
                        Files.writeString(Path.of(path), content);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    return "文件已写入: " + path;
                }
        ));

        // list_dir 工具
        tools.put("list_dir", new Tool(
                "list_dir",
                "列出目录内容，用于查看项目结构、查找文件等",
                createParameters(new Param("path", "string", "目录路径", true)),
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
                args -> {
                    String command = args.get("command");
                    try {
                        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                        pb.redirectErrorStream(true);
                        Process process = pb.start();

                        // 读取命令输出
                        StringBuilder output = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                output.append(line).append("\n");
                            }
                        }

                        int exitCode = process.waitFor();
                        return String.format("命令执行完成 (exit code: %d)\n%s",
                                exitCode, output);
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

        try {
            return tool.executor().execute(args);
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
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

    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }

    private record Param(String name, String type, String description, boolean required) {}
}
