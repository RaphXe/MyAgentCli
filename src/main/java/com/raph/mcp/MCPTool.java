package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record MCPTool(String originalName, String localName, String description, JsonNode inputSchema) {
}
