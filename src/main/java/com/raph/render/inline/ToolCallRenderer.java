package com.raph.render.inline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raph.llm.LlmClient;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToolCallRenderer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream out;
    private final BlockRegistry registry;

    ToolCallRenderer(PrintStream out, BlockRegistry registry) {
        this.out = out == null ? System.out : out;
        this.registry = registry;
    }

    FoldableBlock createBlock(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        Map<String, List<LlmClient.ToolCall>> grouped = group(toolCalls);
        return new FoldableBlock(out, collapsedHeader(grouped), expandedLines(grouped));
    }

    private static Map<String, List<LlmClient.ToolCall>> group(List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall call : toolCalls) {
            String toolName = call == null || call.function() == null ? "(unknown)" : call.function().name();
            grouped.computeIfAbsent(toolName, ignored -> new ArrayList<>()).add(call);
        }
        return grouped;
    }

    private static String collapsedHeader(Map<String, List<LlmClient.ToolCall>> grouped) {
        if (grouped.size() == 1) {
            Map.Entry<String, List<LlmClient.ToolCall>> entry = grouped.entrySet().iterator().next();
            return "⏵ " + toolCollapsedLabel(entry.getKey(), entry.getValue()) + " (ctrl+o to expand)";
        }
        int total = grouped.values().stream().mapToInt(List::size).sum();
        return "⏵ " + grouped.size() + " 组工具调用 / " + total + " 次 (ctrl+o to expand)";
    }

    private static List<String> expandedLines(Map<String, List<LlmClient.ToolCall>> grouped) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<LlmClient.ToolCall>> entry : grouped.entrySet()) {
            String toolName = entry.getKey();
            List<LlmClient.ToolCall> calls = entry.getValue();
            lines.add("  " + toolLabel(toolName, calls.size()));
            for (LlmClient.ToolCall call : calls) {
                String detail = call == null || call.function() == null
                        ? ""
                        : extractKeyParam(toolName, call.function().arguments());
                if (!detail.isBlank()) {
                    lines.add("    └ " + detail);
                }
            }
        }
        return lines;
    }

    private static String toolCollapsedLabel(String toolName, List<LlmClient.ToolCall> calls) {
        int count = calls == null ? 0 : calls.size();
        String label = stripPrefixIcon(toolLabel(toolName, count));
        if (count != 1 || calls == null || calls.isEmpty() || calls.get(0).function() == null) {
            return label;
        }
        String detail = extractKeyParam(toolName, calls.get(0).function().arguments());
        if (detail.isBlank()) {
            return label;
        }
        return switch (toolName) {
            case "read_file" -> "ReadFile(" + detail + ")";
            case "write_file" -> "WriteFile(" + detail + ")";
            case "list_dir" -> "ListDir(" + detail + ")";
            case "project_tree" -> "ProjectTree(" + detail + ")";
            case "search_files" -> "SearchFiles(\"" + detail + "\")";
            case "execute_command" -> "Shell(" + detail + ")";
            default -> label + " · " + detail;
        };
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName == null ? "" : toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "project_tree" -> "🌲 生成 " + count + " 个项目树";
            case "search_files" -> "🔍 搜索文件 " + count + " 次";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? "🔌 调用 MCP 工具 " + formatMcpName(toolName) + (count == 1 ? "" : " × " + count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON.readTree(argsJson);
            String key = switch (toolName == null ? "" : toolName) {
                case "read_file", "write_file", "list_dir", "project_tree" -> "path";
                case "search_files" -> "query";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                default -> null;
            };
            String value = key == null ? argsJson : node.path(key).asText("");
            return truncate(value == null ? "" : value, 80);
        } catch (Exception e) {
            return truncate(argsJson == null ? "" : argsJson, 80);
        }
    }

    private static String formatMcpName(String toolName) {
        String[] parts = toolName.split("__", 3);
        return parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
    }

    private static String stripPrefixIcon(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        int firstSpace = label.indexOf(' ');
        if (firstSpace < 0) {
            return label;
        }
        int codePoint = label.codePointAt(0);
        return codePoint >= 0x2600 && codePoint <= 0x1FAFF
                ? label.substring(firstSpace + 1)
                : label;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
