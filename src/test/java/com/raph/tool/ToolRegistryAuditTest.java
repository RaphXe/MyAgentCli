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
}
