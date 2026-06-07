package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raph.hitl.ApprovalPolicy;
import com.raph.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCPServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void initializesListsAndCallsMcpTool() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        MCPServerConfig config = new MCPServerConfig("fake", "stdio", "unused", List.of(), Map.of(), null, Map.of(), null);
        MCPServer server = new MCPServer(config, new JsonRPCClient(transport, Duration.ofSeconds(1)));
        ToolRegistry registry = new ToolRegistry();

        for (ToolRegistry.Tool tool : server.initializeAndCreateTools()) {
            registry.registerTool(tool);
        }

        assertTrue(registry.hasTool("mcp__fake__echo"));
        assertTrue(ApprovalPolicy.requiresApproval("mcp__fake__echo"));
        assertTrue(ApprovalPolicy.requiresApproval("mcp__fake__echo", registry.toolMetadata("mcp__fake__echo")));
        assertTrue(registry.toolMetadata("mcp__fake__echo").unknownRisk());
        String result = registry.executeTool("mcp__fake__echo", "{\"message\":\"hi\",\"nested\":{\"a\":1}}");
        assertEquals("echo:hi\nstructuredContent={\"receivedNested\":{\"a\":1}}", result);
        assertEquals("notifications/initialized", transport.methods.get(1));
    }

    @Test
    void preservesPrimitiveJsonArgumentTypesForMcpTools() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        MCPServerConfig config = new MCPServerConfig("fake", "stdio", "unused", List.of(), Map.of(), null, Map.of(), null);
        MCPServer server = new MCPServer(config, new JsonRPCClient(transport, Duration.ofSeconds(1)));
        ToolRegistry registry = new ToolRegistry();

        for (ToolRegistry.Tool tool : server.initializeAndCreateTools()) {
            registry.registerTool(tool);
        }

        registry.executeTool("mcp__fake__echo", "{\"message\":\"hi\",\"count\":3,\"enabled\":true,\"nested\":{\"a\":1}}");

        JsonNode arguments = transport.latestToolCall.path("params").path("arguments");
        assertTrue(arguments.path("count").isNumber(), arguments.toString());
        assertTrue(arguments.path("enabled").isBoolean(), arguments.toString());
        assertTrue(arguments.path("nested").isObject(), arguments.toString());
    }

    @Test
    void formatsMcpToolErrorsForModelSelfCorrection() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(true);
        MCPServerConfig config = new MCPServerConfig("fake", "stdio", "unused", List.of(), Map.of(), null, Map.of(), null);
        MCPServer server = new MCPServer(config, new JsonRPCClient(transport, Duration.ofSeconds(1)));
        ToolRegistry registry = new ToolRegistry();

        for (ToolRegistry.Tool tool : server.initializeAndCreateTools()) {
            registry.registerTool(tool);
        }

        String result = registry.executeTool("mcp__fake__echo", "{\"message\":\"bad\"}");

        assertTrue(result.startsWith("[MCP ERROR] failed"));
    }

    @Test
    void appendsConfiguredSkillUsageToMcpToolDescription() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        MCPServerConfig config = new MCPServerConfig(
                "fake",
                "stdio",
                "unused",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                null,
                List.of(),
                Map.of("echo", List.of("core/agent"))
        );
        MCPServer server = new MCPServer(config, new JsonRPCClient(transport, Duration.ofSeconds(1)));

        List<ToolRegistry.Tool> tools = server.initializeAndCreateTools();

        assertTrue(tools.get(0).description().contains("关联 skill"), tools.get(0).description());
        assertTrue(tools.get(0).description().contains("core/agent"), tools.get(0).description());
    }

    @Test
    void configuredReadOnlyMcpToolDoesNotRequireDangerApproval() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        MCPServerConfig config = new MCPServerConfig(
                "fake",
                "stdio",
                "unused",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                null,
                List.of(),
                Map.of(),
                null,
                Map.of("echo", new MCPServerConfig.ToolPolicy(true, null, null, null, null, null))
        );
        MCPServer server = new MCPServer(config, new JsonRPCClient(transport, Duration.ofSeconds(1)));

        ToolRegistry.Tool tool = server.initializeAndCreateTools().get(0);

        assertEquals("mcp__fake__echo", tool.name());
        assertFalse(tool.metadata().requiresApproval());
        assertFalse(ApprovalPolicy.requiresApproval(tool.name(), tool.metadata()));
        assertEquals("🟢 安全", tool.metadata().dangerLevel());
    }

    @Test
    void configuredMcpMutationCarriesPathMetadata() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        MCPServerConfig config = new MCPServerConfig(
                "fake",
                "stdio",
                "unused",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                null,
                List.of(),
                Map.of(),
                null,
                Map.of("echo", new MCPServerConfig.ToolPolicy(false, true, true, "path", "🔴 高危", "writes"))
        );
        MCPServer server = new MCPServer(config, new JsonRPCClient(transport, Duration.ofSeconds(1)));

        ToolRegistry.Tool tool = server.initializeAndCreateTools().get(0);

        assertTrue(tool.metadata().requiresApproval());
        assertTrue(tool.metadata().mutatesFile());
        assertEquals("path", tool.metadata().pathArgument());
        assertEquals("writes", tool.metadata().riskDescription());
    }

    private static final class ScriptedTransport implements MCPTransport {
        private final List<String> methods = new ArrayList<>();
        private final boolean toolError;
        private JsonNode latestToolCall;

        private ScriptedTransport() {
            this(false);
        }

        private ScriptedTransport(boolean toolError) {
            this.toolError = toolError;
        }

        @Override
        public void start() {
        }

        @Override
        public JsonNode send(JsonNode message, Duration timeout) throws MCPException {
            methods.add(message.path("method").asText());
            if (message.path("id").isMissingNode()) {
                return null;
            }
            String method = message.path("method").asText();
            long id = message.path("id").asLong();
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            ObjectNode result = response.putObject("result");
            if ("initialize".equals(method)) {
                result.put("protocolVersion", "2025-06-18");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "fake");
                return response;
            }
            if ("tools/list".equals(method)) {
                ObjectNode tool = result.putArray("tools").addObject();
                tool.put("name", "echo");
                tool.put("description", "Echo input");
                ObjectNode schema = tool.putObject("inputSchema");
                schema.put("type", "object");
                schema.putObject("properties").putObject("message").put("type", "string");
                schema.putArray("required").add("message");
                return response;
            }
            if ("tools/call".equals(method)) {
                latestToolCall = message;
                if (toolError) {
                    result.put("isError", true);
                    result.putArray("content").addObject().put("type", "text").put("text", "failed");
                    return response;
                }
                JsonNode args = message.path("params").path("arguments");
                result.put("isError", false);
                result.putArray("content").addObject().put("type", "text")
                        .put("text", "echo:" + args.path("message").asText());
                result.putObject("structuredContent").set("receivedNested", args.path("nested"));
                return response;
            }
            throw new MCPException("unexpected method " + method);
        }

        @Override
        public void close() {
        }
    }
}
