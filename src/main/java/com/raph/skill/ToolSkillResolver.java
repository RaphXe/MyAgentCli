package com.raph.skill;

import com.raph.llm.LlmClient;
import com.raph.mcp.MCPServerConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ToolSkillResolver {
    private static final Map<String, List<String>> TOOL_SKILL_IDS = new ConcurrentHashMap<>();

    private final SkillRepository repository;

    public ToolSkillResolver(SkillRepository repository) {
        this.repository = repository == null ? SkillRepository.defaultRepository() : repository;
    }

    public static ToolSkillResolver defaults() {
        return new ToolSkillResolver(SkillRepository.defaultRepository());
    }

    public static void registerToolSkillIds(String localToolName, List<String> skillIds) {
        if (localToolName == null || localToolName.isBlank() || skillIds == null || skillIds.isEmpty()) {
            return;
        }
        TOOL_SKILL_IDS.put(localToolName.trim(), List.copyOf(skillIds));
    }

    public String renderMcpToolUsage(MCPServerConfig config, String originalToolName) {
        if (config == null) {
            return "";
        }
        List<String> ids = skillIds(config.skills(), config.toolSkills(), originalToolName);
        return repository.renderSkills(ids, "MCP skill usage");
    }

    public String renderToolCallUsage(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "";
        }
        Set<String> ids = new LinkedHashSet<>();
        for (LlmClient.ToolCall call : toolCalls) {
            String localToolName = toolName(call);
            List<String> explicit = TOOL_SKILL_IDS.get(localToolName);
            if (explicit != null) {
                ids.addAll(explicit);
            }
            McpToolName parsed = McpToolName.parse(localToolName);
            if (parsed == null) {
                continue;
            }
            ids.add("mcp/" + parsed.server());
            ids.add("mcp/" + parsed.server() + "/common");
            ids.add("mcp/" + parsed.server() + "/" + parsed.tool());
        }
        return repository.renderSkills(new ArrayList<>(ids), "以下是即将调用的 MCP 工具关联 skill。请先根据这些说明修正参数、确认边界，再决定是否继续调用工具。");
    }

    public static List<String> skillIds(List<String> serverSkills, Map<String, List<String>> toolSkills, String originalToolName) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (serverSkills != null) {
            ids.addAll(serverSkills);
        }
        if (toolSkills != null) {
            List<String> wildcard = toolSkills.get("*");
            if (wildcard != null) {
                ids.addAll(wildcard);
            }
            List<String> exact = toolSkills.get(originalToolName);
            if (exact != null) {
                ids.addAll(exact);
            }
        }
        return List.copyOf(ids);
    }

    private static String toolName(LlmClient.ToolCall call) {
        if (call == null || call.function() == null || call.function().name() == null) {
            return "";
        }
        return call.function().name();
    }

    private record McpToolName(String server, String tool) {
        static McpToolName parse(String localName) {
            if (localName == null || !localName.startsWith("mcp__")) {
                return null;
            }
            String[] parts = localName.split("__", 3);
            if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
                return null;
            }
            return new McpToolName(parts[1], parts[2]);
        }
    }
}
