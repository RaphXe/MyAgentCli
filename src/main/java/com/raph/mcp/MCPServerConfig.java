package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MCPServerConfig(
        String name,
        String type,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        Integer initTimeoutSeconds,
        List<String> skills,
        Map<String, List<String>> toolSkills,
        ToolPolicy defaultToolPolicy,
        Map<String, ToolPolicy> toolPolicies
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public MCPServerConfig(String name,
                           String type,
                           String command,
                           List<String> args,
                           Map<String, String> env,
                           String url,
                           Map<String, String> headers,
                           Integer initTimeoutSeconds) {
        this(name, type, command, args, env, url, headers, initTimeoutSeconds, List.of(), Map.of(), null, Map.of());
    }

    public MCPServerConfig(String name,
                           String type,
                           String command,
                           List<String> args,
                           Map<String, String> env,
                           String url,
                           Map<String, String> headers,
                           Integer initTimeoutSeconds,
                           List<String> skills,
                           Map<String, List<String>> toolSkills) {
        this(name, type, command, args, env, url, headers, initTimeoutSeconds, skills, toolSkills, null, Map.of());
    }

    public MCPServerConfig {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        skills = skills == null ? List.of() : List.copyOf(skills);
        toolSkills = toolSkills == null ? Map.of() : copyToolSkills(toolSkills);
        defaultToolPolicy = defaultToolPolicy == null ? null : defaultToolPolicy.normalized();
        toolPolicies = toolPolicies == null ? Map.of() : copyToolPolicies(toolPolicies);
    }

    public static List<MCPServerConfig> load(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        JsonNode root = MAPPER.readTree(Files.readString(path));
        JsonNode servers = root.path("servers");
        if (!servers.isObject()) {
            return List.of();
        }
        List<MCPServerConfig> configs = new ArrayList<>();
        servers.fields().forEachRemaining(entry -> configs.add(fromNode(entry.getKey(), entry.getValue())));
        return List.copyOf(configs);
    }

    private static MCPServerConfig fromNode(String name, JsonNode node) {
        return new MCPServerConfig(
                sanitizeName(name),
                text(node, "type", "stdio"),
                text(node, "command", null),
                stringList(node.path("args")),
                stringMap(node.path("env")),
                text(node, "url", null),
                stringMap(node.path("headers")),
                intValue(node, "init_timeout_seconds"),
                stringList(node.path("skills")),
                stringListMap(node.path("tool_skills")),
                toolPolicy(node.path("default_tool_policy")),
                toolPolicyMap(node.path("tool_policies"))
        );
    }

    public ToolPolicy policyForTool(String originalToolName) {
        ToolPolicy exact = originalToolName == null ? null : toolPolicies.get(originalToolName);
        if (exact != null) {
            return mergePolicies(defaultPolicy(), exact);
        }
        return defaultPolicy();
    }

    private ToolPolicy defaultPolicy() {
        ToolPolicy wildcard = toolPolicies.get("*");
        return mergePolicies(defaultToolPolicy, wildcard);
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "server";
        }
        return name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        String text = substituteEnv(value.asText());
        return text == null || text.isBlank() ? defaultValue : text.trim();
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                values.add(substituteEnv(item.asText()));
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isNull()) {
                values.put(entry.getKey(), substituteEnv(value.asText()));
            }
        });
        return Map.copyOf(values);
    }

    private static Map<String, List<String>> stringListMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            List<String> list = stringList(entry.getValue());
            if (!list.isEmpty()) {
                values.put(entry.getKey(), list);
            }
        });
        return copyToolSkills(values);
    }

    private static Map<String, List<String>> copyToolSkills(Map<String, List<String>> source) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isEmpty()) {
                values.put(key.trim(), List.copyOf(value));
            }
        });
        return Map.copyOf(values);
    }

    private static Map<String, ToolPolicy> toolPolicyMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, ToolPolicy> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            ToolPolicy policy = toolPolicy(entry.getValue());
            if (policy != null && entry.getKey() != null && !entry.getKey().isBlank()) {
                values.put(entry.getKey().trim(), policy);
            }
        });
        return copyToolPolicies(values);
    }

    private static Map<String, ToolPolicy> copyToolPolicies(Map<String, ToolPolicy> source) {
        Map<String, ToolPolicy> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                values.put(key.trim(), value.normalized());
            }
        });
        return Map.copyOf(values);
    }

    private static ToolPolicy toolPolicy(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new ToolPolicy(
                booleanValue(node, "read_only"),
                booleanValue(node, "requires_approval"),
                booleanValue(node, "mutates_file"),
                text(node, "path_argument", null),
                text(node, "danger_level", null),
                text(node, "risk_description", null)
        ).normalized();
    }

    private static ToolPolicy mergePolicies(ToolPolicy base, ToolPolicy override) {
        if (base == null) {
            return override == null ? null : override.normalized();
        }
        if (override == null) {
            return base.normalized();
        }
        return new ToolPolicy(
                override.readOnly() == null ? base.readOnly() : override.readOnly(),
                override.requiresApproval() == null ? base.requiresApproval() : override.requiresApproval(),
                override.mutatesFile() == null ? base.mutatesFile() : override.mutatesFile(),
                override.pathArgument() == null ? base.pathArgument() : override.pathArgument(),
                override.dangerLevel() == null ? base.dangerLevel() : override.dangerLevel(),
                override.riskDescription() == null ? base.riskDescription() : override.riskDescription()
        ).normalized();
    }

    private static String substituteEnv(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String result = value;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")
                .matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String replacement = System.getenv(matcher.group(1));
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(sb);
        result = sb.toString();
        return result;
    }

    private static Integer intValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            int parsed = value.asInt();
            return parsed > 0 ? parsed : null;
        }
        try {
            int parsed = Integer.parseInt(value.asText().trim());
            return parsed > 0 ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean booleanValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return switch (text.trim().toLowerCase()) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> null;
        };
    }

    public record ToolPolicy(Boolean readOnly,
                             Boolean requiresApproval,
                             Boolean mutatesFile,
                             String pathArgument,
                             String dangerLevel,
                             String riskDescription) {
        private ToolPolicy normalized() {
            return new ToolPolicy(
                    readOnly,
                    requiresApproval,
                    mutatesFile,
                    blankToNull(pathArgument),
                    blankToNull(dangerLevel),
                    blankToNull(riskDescription)
            );
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
