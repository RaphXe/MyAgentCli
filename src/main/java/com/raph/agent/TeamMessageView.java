package com.raph.agent;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable UI snapshot of a team message.
 */
public record TeamMessageView(
        String id,
        String threadId,
        String senderId,
        String receiverId,
        String type,
        String contentSummary,
        Map<String, String> metadata,
        Instant createdAt
) {
    private static final int SUMMARY_LIMIT = 240;

    public TeamMessageView {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static TeamMessageView from(AgentMessage message) {
        if (message == null) {
            return new TeamMessageView("", "", "", "", "", "", Map.of(), null);
        }
        return new TeamMessageView(
                message.id(),
                message.threadId(),
                message.senderId(),
                message.receiverId(),
                message.type() == null ? "" : message.type().name(),
                summarize(message.content()),
                message.metadata(),
                message.timestamp()
        );
    }

    private static String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= SUMMARY_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, SUMMARY_LIMIT) + "...";
    }
}
