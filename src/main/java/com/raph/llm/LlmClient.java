package com.raph.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

public interface LlmClient {

    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;

    record ContentPart(String type, String text, String imageBase64, String imageUrl, String mimeType) {
        public static ContentPart text(String text) {
            return new ContentPart("text", text, null, null, null);
        }

        public static ContentPart imageBase64(String imageBase64, String mimeType) {
            return new ContentPart("image_base64", null, imageBase64, null,
                    mimeType == null || mimeType.isBlank() ? "image/png" : mimeType);
        }

        public static ContentPart imageUrl(String imageUrl) {
            return new ContentPart("image_url", null, null, imageUrl, null);
        }

        public boolean isText() {
            return "text".equals(type);
        }

        public boolean isImage() {
            return "image_base64".equals(type) || "image_url".equals(type);
        }
    }

    record ToolCall(String id, Function function) {
        public record Function(String name, String arguments) {}
    }

    record Tool(String name, String description, JsonNode parameters) {}

    record Message(String role, String content
            , String reasoningContent, List<ToolCall> toolCalls
            , String toolCallId, List<ContentPart> contentParts) {

        public Message(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                       String toolCallId) {
            this(role, content, reasoningContent, toolCalls, toolCallId, null);
        }

        public Message(String role, String content) {
            this(role, content, null, null, null);
        }

        public static Message system(String content) {
            return new Message("system", content, null, null, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null,  null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, null, toolCallId, null);
        }
    }

    interface StreamListener {
        StreamListener NO_OP = new StreamListener() {};

        default void onReasoningDelta(String delta) {}

        default void onContentDelta(String delta) {}
    }

    record ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                        int inputTokens, int outputTokens, int cachedInputTokens) {
        public ChatResponse(String role, String content, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, null, toolCalls, inputTokens, outputTokens, 0);
        }

        public ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, reasoningContent, toolCalls, inputTokens, outputTokens, 0);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        public String getContent() {
            return content;
        }
    }
}
