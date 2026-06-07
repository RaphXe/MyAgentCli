package com.raph.mcp;

import java.util.List;

public record MCPToolResult(boolean error, List<String> content, String structuredContent) {
}
