package com.raph.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * LLM 每轮输出的结构化自治决策。Runtime 解析并执行 actions。
 */
public record AgentDecision(String status, List<Action> actions, String finalAnswer) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Action(
            String type,
            String to,
            String messageType,
            String content,
            String taskId,
            String title,
            String description,
            List<String> dependencies,
            String artifact,
            String note,
            String reason,
            Map<String, String> params
    ) {}

    public static AgentDecision empty() {
        return new AgentDecision("idle", List.of(), null);
    }

    public static AgentDecision fallback(String content) {
        if (content == null || content.isBlank()) return empty();
        return new AgentDecision("reported", List.of(new Action(
                "send_message", AgentMessage.BROADCAST, "REPORT_PROGRESS", content,
                null, null, null, List.of(), null, null, null, Map.of()
        )), null);
    }

    public static AgentDecision parse(String raw) {
        if (raw == null || raw.isBlank()) return empty();
        String cleaned = extractJsonObject(stripCodeFence(raw.trim()));
        try {
            JsonNode root = MAPPER.readTree(cleaned);
            String status = text(root, "status", "working");
            String finalAnswer = text(root, "final_answer", null);
            List<Action> actions = new ArrayList<>();
            JsonNode actionsNode = root.path("actions");
            if (actionsNode.isArray()) {
                for (JsonNode node : actionsNode) {
                    actions.add(new Action(
                            text(node, "type", "send_message"),
                            text(node, "to", null),
                            text(node, "message_type", null),
                            text(node, "content", null),
                            text(node, "task_id", null),
                            text(node, "title", null),
                            text(node, "description", null),
                            stringList(node.path("dependencies")),
                            text(node, "artifact", null),
                            text(node, "note", null),
                            text(node, "reason", null),
                            params(node)
                    ));
                }
            }
            return new AgentDecision(status, List.copyOf(actions), finalAnswer);
        } catch (Exception ignored) {
            return fallback(raw);
        }
    }


    private static Map<String, String> params(JsonNode node) {
        Map<String, String> values = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (List.of("type", "to", "message_type", "content", "task_id", "title", "description",
                    "dependencies", "artifact", "note", "reason").contains(key)) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                values.put(key, null);
            } else if (value.isValueNode()) {
                values.put(key, value.asText());
            } else {
                values.put(key, value.toString());
            }
        }
        return Map.copyOf(values);
    }

    private static String extractJsonObject(String value) {
        if (value == null) return "";
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1).trim();
        }
        return value.trim();
    }

    private static String stripCodeFence(String value) {
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "");
            value = value.replaceFirst("\\s*```$", "");
        }
        return value.trim();
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asText();
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isNull()) values.add(item.asText());
        }
        return List.copyOf(values);
    }
}
