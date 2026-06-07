package com.raph.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MCPServerConfigTest {
    @Test
    void loadsSkillAssociationsFromConfig() throws Exception {
        Path config = Files.createTempFile("mcp-config", ".json");
        Files.writeString(config, """
                {
                  "servers": {
                    "demo": {
                      "type": "stdio",
                      "command": "demo",
                      "skills": ["mcp/demo"],
                      "tool_skills": {
                        "*": ["mcp/demo/common"],
                        "search": ["mcp/demo/search"]
                      }
                    }
                  }
                }
                """);

        MCPServerConfig server = MCPServerConfig.load(config).get(0);

        assertEquals(List.of("mcp/demo"), server.skills());
        assertEquals(List.of("mcp/demo/common"), server.toolSkills().get("*"));
        assertEquals(List.of("mcp/demo/search"), server.toolSkills().get("search"));
    }
}
