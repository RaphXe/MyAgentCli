package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StdioMCPTransportTest {
    @Test
    void matchesNumericIdsByValueAcrossJsonNodeTypes() throws Exception {
        String script = "while IFS= read -r line; do "
                + "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}'; "
                + "done";
        StdioMCPTransport transport = new StdioMCPTransport("sh", List.of("-c", script), Map.of());
        JsonRPCClient client = new JsonRPCClient(transport, Duration.ofSeconds(2));

        client.start();
        try {
            JsonNode result = client.request("initialize", null);
            assertEquals(true, result.path("ok").asBoolean());
        } finally {
            client.close();
        }
    }
}
