package com.raph.hitl;

import com.raph.tool.ToolRegistry;

import java.util.Set;

/**
 * 危险操作识别策略。
 */
public final class ApprovalPolicy {
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "write_file",
            "execute_command",
            "create_project"
    );

    private ApprovalPolicy() {
    }

    public static boolean requiresApproval(String toolName) {
        return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);
    }

    public static boolean requiresApproval(String toolName, ToolRegistry.ToolMetadata metadata) {
        if (metadata != null) {
            return metadata.requiresApproval();
        }
        return requiresApproval(toolName);
    }

    public static String getDangerLevel(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "🔴 高危";
            case "write_file", "create_project" -> "🟡 中危";
            default -> isMcpTool(toolName) ? "🟡 MCP" : "🟢 安全";
        };
    }

    public static String getDangerLevel(String toolName, ToolRegistry.ToolMetadata metadata) {
        if (metadata != null && metadata.dangerLevel() != null && !metadata.dangerLevel().isBlank()) {
            return metadata.dangerLevel();
        }
        return getDangerLevel(toolName);
    }

    public static String getRiskDescription(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "write_file" -> "将写入文件内容；覆盖模式可能导致原有内容丢失，追加模式会在文件末尾追加内容";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> isMcpTool(toolName)
                    ? "将调用外部 MCP server 提供的工具，可能访问网络、文件或第三方服务"
                    : "安全的只读操作";
        };
    }

    public static String getRiskDescription(String toolName, ToolRegistry.ToolMetadata metadata) {
        if (metadata != null && metadata.riskDescription() != null && !metadata.riskDescription().isBlank()) {
            return metadata.riskDescription();
        }
        return getRiskDescription(toolName);
    }

    public static Set<String> getDangerousTools() {
        return DANGEROUS_TOOLS;
    }

    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    public static String mcpServerName(String toolName) {
        if (!isMcpTool(toolName)) {
            return null;
        }
        String[] parts = toolName.split("__", 3);
        return parts.length >= 2 ? parts[1] : null;
    }
}
