package com.raph.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class AbstractOpenaiClient implements LlmClient {
    protected static final ObjectMapper mapper = new ObjectMapper();

    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(readTimeoutSeconds("paicli.llm.connect.timeout.seconds", 60), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds("paicli.llm.read.timeout.seconds", 300), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds("paicli.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds("paicli.llm.call.timeout.seconds", 600), TimeUnit.SECONDS)
            .build();

    private static long readTimeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", getModel());
        ArrayNode messagesArray = requestBody.putArray("messages");
        for(Message msg : messages) {
            ObjectNode messageBody = messagesArray.addObject();
            messageBody.put("role", msg.role());
            messageBody.put("content", msg.content());

            // 如果有工具调用，序列化 tool_calls
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = messageBody.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            // 如果是工具结果，添加 tool_call_id
            if (msg.toolCallId() != null) {
                messageBody.put("tool_call_id", msg.toolCallId());
            }
        }

        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
                .post(body)
                .build();

        try (Response response = SHARED_HTTP_CLIENT.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("OpenAI API request failed: HTTP " + response.code() + " " + response.message()
                        + (responseBody.isBlank() ? "" : ", body: " + responseBody));
            }

            JsonNode root = mapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IOException("OpenAI API response missing choices: " + responseBody);
            }

            JsonNode message = choices.get(0).path("message");
            String role = textOrDefault(message.path("role"), "assistant");
            String content = textOrNull(message.path("content"));
            String reasoningContent = firstText(message, "reasoning_content", "reasoningContent");
            List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));

            JsonNode usage = root.path("usage");
            int inputTokens = firstInt(usage, "prompt_tokens", "input_tokens");
            int outputTokens = firstInt(usage, "completion_tokens", "output_tokens");
            int cachedInputTokens = firstInt(usage.path("prompt_tokens_details"), "cached_tokens");
            if (cachedInputTokens == 0) {
                cachedInputTokens = firstInt(usage, "prompt_cache_hit_tokens", "cached_input_tokens");
            }

            return new ChatResponse(role, content, reasoningContent, toolCalls,
                    inputTokens, outputTokens, cachedInputTokens);
        }
    }

    private static List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (!toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode toolCallNode : toolCallsNode) {
            JsonNode functionNode = toolCallNode.path("function");
            String name = textOrNull(functionNode.path("name"));
            String arguments = textOrDefault(functionNode.path("arguments"), "{}");
            toolCalls.add(new ToolCall(
                    textOrNull(toolCallNode.path("id")),
                    new ToolCall.Function(name, arguments)
            ));
        }
        return toolCalls;
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = textOrNull(node.path(fieldName));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static int firstInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isNumber()) {
                return value.asInt();
            }
        }
        return 0;
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        String value = textOrNull(node);
        return value == null ? defaultValue : value;
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    protected abstract String getApiUrl();

    protected abstract String getModel();

    protected abstract String getApiKey();
}
