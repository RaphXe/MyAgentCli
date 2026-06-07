package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raph.skill.ToolSkillResolver;
import com.raph.tool.ToolRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MCPServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    private final MCPServerConfig config;
    private final JsonRPCClient client;
    private final ToolSkillResolver skillResolver;
    private String protocolVersion = DEFAULT_PROTOCOL_VERSION;

    public MCPServer(MCPServerConfig config) {
        this(config, createClient(config));
    }

    MCPServer(MCPServerConfig config, JsonRPCClient client) {
        this.config = config;
        this.client = client;
        this.skillResolver = ToolSkillResolver.defaults();
    }

    public String name() {
        return config.name();
    }

    public String diagnostics() {
        return client.diagnostics();
    }

    public String logs() {
        return client.logs();
    }

    public List<ToolRegistry.Tool> initializeAndCreateTools() throws MCPException {
        client.start();
        initialize();
        client.notify("notifications/initialized", null);
        List<MCPTool> mcpTools = listTools();
        List<ToolRegistry.Tool> tools = new ArrayList<>();
        for (MCPTool mcpTool : mcpTools) {
            ToolSkillResolver.registerToolSkillIds(
                    mcpTool.localName(),
                    ToolSkillResolver.skillIds(config.skills(), config.toolSkills(), mcpTool.originalName())
            );
            tools.add(ToolRegistry.Tool.json(
                    mcpTool.localName(),
                    descriptionWithSkillUsage(mcpTool),
                    mcpTool.inputSchema(),
                    metadataFor(mcpTool.originalName()),
                    args -> callToolAsString(mcpTool.originalName(), args)
            ));
        }
        return List.copyOf(tools);
    }

    private ToolRegistry.ToolMetadata metadataFor(String originalToolName) {
        MCPServerConfig.ToolPolicy policy = config.policyForTool(originalToolName);
        if (policy == null) {
            return ToolRegistry.ToolMetadata.externalMcpDefault(config.name());
        }
        boolean mutatesFile = Boolean.TRUE.equals(policy.mutatesFile());
        boolean requiresApproval;
        if (policy.requiresApproval() != null) {
            requiresApproval = policy.requiresApproval();
        } else if (Boolean.TRUE.equals(policy.readOnly()) && !mutatesFile) {
            requiresApproval = false;
        } else {
            requiresApproval = true;
        }
        String dangerLevel = firstNonBlank(policy.dangerLevel(), requiresApproval ? "🟡 MCP" : "🟢 安全");
        String riskDescription = firstNonBlank(policy.riskDescription(),
                requiresApproval
                        ? "将调用 MCP server [" + config.name() + "] 提供的外部工具"
                        : "配置声明为 MCP 只读工具");
        return new ToolRegistry.ToolMetadata(
                mutatesFile,
                policy.pathArgument(),
                requiresApproval,
                false,
                dangerLevel,
                riskDescription
        );
    }

    private void initialize() throws MCPException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", DEFAULT_PROTOCOL_VERSION);
        ObjectNode capabilities = params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "PaiCli");
        clientInfo.put("version", "1.0-SNAPSHOT");

        JsonNode result = client.request("initialize", params);
        JsonNode version = result.path("protocolVersion");
        if (version.isTextual() && !version.asText().isBlank()) {
            protocolVersion = version.asText();
        }
    }

    private List<MCPTool> listTools() throws MCPException {
        List<MCPTool> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = MAPPER.createObjectNode();
            if (cursor != null && !cursor.isBlank()) {
                params.put("cursor", cursor);
            }
            JsonNode result = client.request("tools/list", params);
            JsonNode toolsNode = result.path("tools");
            if (toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    String originalName = text(toolNode, "name", null);
                    if (originalName == null || originalName.isBlank()) {
                        continue;
                    }
                    String localName = localToolName(config.name(), originalName);
                    String description = "[MCP:" + config.name() + "] "
                            + text(toolNode, "description", text(toolNode, "title", originalName));
                    JsonNode inputSchema = toolNode.path("inputSchema");
                    if (inputSchema.isMissingNode() || inputSchema.isNull()) {
                        inputSchema = defaultInputSchema();
                    }
                    tools.add(new MCPTool(originalName, localName, description, inputSchema));
                }
            }
            cursor = text(result, "nextCursor", null);
        } while (cursor != null && !cursor.isBlank());
        return List.copyOf(tools);
    }

    private String callToolAsString(String originalName, Map<String, JsonNode> args) {
        try {
            JsonNode result = callTool(originalName, args);
            return formatToolResult(result);
        } catch (MCPException e) {
            return "[MCP ERROR] " + e.getMessage();
        }
    }

    private String descriptionWithSkillUsage(MCPTool mcpTool) {
        String usage = skillResolver.renderMcpToolUsage(config, mcpTool.originalName());
        if (usage == null || usage.isBlank()) {
            return mcpTool.description();
        }
        return mcpTool.description() + "\n\n关联 skill:\n" + truncate(usage, 1200);
    }

    private JsonNode callTool(String originalName, Map<String, JsonNode> args) throws MCPException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", originalName);
        ObjectNode arguments = params.putObject("arguments");
        if (args != null) {
            args.forEach((key, value) -> arguments.set(key, value == null ? MAPPER.nullNode() : value));
        }
        return client.request("tools/call", params);
    }

    private String formatToolResult(JsonNode result) {
        boolean isError = result.path("isError").asBoolean(false);
        List<String> parts = new ArrayList<>();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String type = text(item, "type", "");
                if ("text".equals(type)) {
                    parts.add(text(item, "text", ""));
                } else if (!item.isMissingNode() && !item.isNull()) {
                    parts.add(item.toString());
                }
            }
        }
        JsonNode structured = result.path("structuredContent");
        if (!structured.isMissingNode() && !structured.isNull()) {
            parts.add("structuredContent=" + structured);
        }
        if (parts.isEmpty()) {
            parts.add(result.toString());
        }
        String output = String.join("\n", parts);
        return isError ? "[MCP ERROR] " + output : output;
    }

    private static JsonNode defaultInputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode());
        schema.set("required", MAPPER.createArrayNode());
        return schema;
    }

    private static String localToolName(String serverName, String originalName) {
        return "mcp__" + sanitize(serverName) + "__" + sanitize(originalName);
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? defaultValue : text;
    }

    private static JsonRPCClient createClient(MCPServerConfig config) {
        MCPTransport transport;
        if ("streamable_http".equalsIgnoreCase(config.type()) || "http".equalsIgnoreCase(config.type())) {
            transport = new StreamableHttpMCPTransport(config.url(), config.headers());
        } else {
            transport = new StdioMCPTransport(config.command(), config.args(), config.env());
        }
        int timeoutSeconds = config.initTimeoutSeconds() == null || config.initTimeoutSeconds() <= 0
                ? 90
                : config.initTimeoutSeconds();
        return new JsonRPCClient(transport, Duration.ofSeconds(timeoutSeconds));
    }

    @Override
    public void close() {
        client.close();
    }
}
