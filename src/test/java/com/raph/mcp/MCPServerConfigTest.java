package com.raph.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void loadsMcpToolPoliciesFromConfig() throws Exception {
        Path config = Files.createTempFile("mcp-config-policy", ".json");
        Files.writeString(config, """
                {
                  "servers": {
                    "demo": {
                      "type": "stdio",
                      "command": "demo",
                      "default_tool_policy": {
                        "read_only": true
                      },
                      "tool_policies": {
                        "write": {
                          "read_only": false,
                          "requires_approval": true,
                          "mutates_file": true,
                          "path_argument": "path",
                          "danger_level": "🔴 高危",
                          "risk_description": "writes a file"
                        }
                      }
                    }
                  }
                }
                """);

        MCPServerConfig server = MCPServerConfig.load(config).get(0);

        assertTrue(server.policyForTool("search").readOnly());
        MCPServerConfig.ToolPolicy write = server.policyForTool("write");
        assertFalse(write.readOnly());
        assertTrue(write.requiresApproval());
        assertTrue(write.mutatesFile());
        assertEquals("path", write.pathArgument());
        assertEquals("writes a file", write.riskDescription());
    }
}
