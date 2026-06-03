package com.raph.agent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 间通信消息。MessageBus 只负责投递，消息语义由 Type 表达。
 */
public record AgentMessage(
        String id,
        String threadId,
        String senderId,
        String receiverId,
        Type type,
        String content,
        Map<String, String> metadata,
        Instant timestamp
) {
    public static final String BROADCAST = "*";

    public enum Type {
        GOAL,
        PROPOSE,
        REQUEST_HELP,
        DELEGATE,
        CLAIM_TASK,
        REPORT_PROGRESS,
        CHALLENGE,
        ANSWER,
        REVIEW,
        APPROVE,
        REJECT,
        BLOCKED,
        FINAL
    }

    public AgentMessage {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (threadId == null || threadId.isBlank()) threadId = "default";
        if (senderId == null || senderId.isBlank()) senderId = "system";
        if (receiverId == null || receiverId.isBlank()) receiverId = BROADCAST;
        if (type == null) type = Type.REPORT_PROGRESS;
        if (content == null) content = "";
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static AgentMessage of(String threadId, String senderId, String receiverId,
                                  Type type, String content) {
        return new AgentMessage(null, threadId, senderId, receiverId, type, content, Map.of(), null);
    }

    public static AgentMessage withMetadata(String threadId, String senderId, String receiverId,
                                            Type type, String content, Map<String, String> metadata) {
        return new AgentMessage(null, threadId, senderId, receiverId, type, content, metadata, null);
    }
}
