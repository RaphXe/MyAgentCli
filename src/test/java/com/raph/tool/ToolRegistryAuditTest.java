package com.raph.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryAuditTest {
    @Test
    void recordsToolAuditEventsWithSummaries() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("list_dir", "{\"path\":\".\"}");

        assertTrue(result.contains("目录内容"), result);
        assertEquals(1, registry.recentAuditEvents().size());
        ToolRegistry.ToolAuditEvent event = registry.recentAuditEvents().get(0);
        assertEquals("list_dir", event.toolName());
        assertTrue(event.argumentsSummary().contains("\"path\":\".\""), event.argumentsSummary());
        assertTrue(event.resultSummary().contains("目录内容"), event.resultSummary());
        assertTrue(event.elapsedMillis() >= 0);
    }

    @Test
    void exposesToolSummariesForCommandUx() {
        ToolRegistry registry = new ToolRegistry();

        java.util.List<ToolRegistry.ToolSummary> summaries = registry.toolSummaries();

        assertTrue(summaries.stream().anyMatch(tool -> "read_file".equals(tool.name())
                && !tool.requiresApproval()));
        assertTrue(summaries.stream().anyMatch(tool -> "write_file".equals(tool.name())
                && tool.requiresApproval()
                && tool.mutatesFile()));
        assertTrue(summaries.stream().anyMatch(tool -> "execute_command".equals(tool.name())
                && tool.requiresApproval()
                && tool.dangerLevel().contains("高危")));
    }

    @Test
    void toolSummariesHideMcpSkillUsageFromDisplayDescription() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerTool(new ToolRegistry.Tool(
                "mcp__demo__search",
                "[MCP:demo] 搜索资料\n\n关联 skill:\nMCP skill usage\n## mcp/demo\n不要在 /tools 里展示这段",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                ToolRegistry.ToolMetadata.externalMcpDefault("demo"),
                args -> "ok"
        ));

        ToolRegistry.ToolSummary summary = registry.toolSummaries().stream()
                .filter(tool -> "mcp__demo__search".equals(tool.name()))
                .findFirst()
                .orElseThrow();

        assertEquals("[MCP:demo] 搜索资料", summary.description());
    }
}
