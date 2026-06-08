package com.raph.render;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured render event for CLI/TUI output.
 */
public record RenderEvent(Type type, String scope, String id, String text, Map<String, String> metadata) {
    public enum Type {
        TEXT,
        LINE,
        STATUS,
        ACTIVITY,
        STREAM_START,
        STREAM_DELTA,
        STREAM_END,
        PLAN_CREATED,
        PLAN_STARTED,
        PLAN_TASK_STARTED,
        PLAN_TASK_COMPLETED,
        PLAN_TASK_FAILED,
        TOOL_STARTED,
        TOOL_FINISHED,
        TEAM_LOG,
        ERROR,
        TOKEN_USAGE
    }

    public RenderEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RenderEvent text(String text) {
        return new RenderEvent(Type.TEXT, null, null, text, Map.of());
    }

    public static RenderEvent line(String text) {
        return new RenderEvent(Type.LINE, null, null, text, Map.of());
    }

    public static RenderEvent status(String text) {
        return new RenderEvent(Type.STATUS, null, null, text, Map.of());
    }

    public static RenderEvent activity(String scope, String text) {
        return new RenderEvent(Type.ACTIVITY, scope, null, text, Map.of());
    }

    public static RenderEvent streamStart(String scope, String id, String prefix) {
        return new RenderEvent(Type.STREAM_START, scope, id, prefix, Map.of());
    }

    public static RenderEvent streamDelta(String scope, String id, String delta) {
        return new RenderEvent(Type.STREAM_DELTA, scope, id, delta, Map.of());
    }

    public static RenderEvent streamEnd(String scope, String id) {
        return new RenderEvent(Type.STREAM_END, scope, id, null, Map.of());
    }

    public static RenderEvent planCreated(String id, String text) {
        return new RenderEvent(Type.PLAN_CREATED, "plan", id, text, Map.of());
    }

    public static RenderEvent planStarted(String id, String text) {
        return new RenderEvent(Type.PLAN_STARTED, "plan", id, text, Map.of());
    }

    public static RenderEvent planTaskStarted(String taskId, String description) {
        return new RenderEvent(Type.PLAN_TASK_STARTED, "plan", taskId, description, Map.of());
    }

    public static RenderEvent planTaskCompleted(String taskId, String description) {
        return new RenderEvent(Type.PLAN_TASK_COMPLETED, "plan", taskId, description, Map.of());
    }

    public static RenderEvent planTaskFailed(String taskId, String message) {
        return new RenderEvent(Type.PLAN_TASK_FAILED, "plan", taskId, message, Map.of());
    }

    public static RenderEvent toolStarted(String scope, String toolName) {
        return new RenderEvent(Type.TOOL_STARTED, scope, toolName, toolName, Map.of());
    }

    public static RenderEvent toolFinished(String scope, String toolName) {
        return new RenderEvent(Type.TOOL_FINISHED, scope, toolName, toolName, Map.of());
    }

    public static RenderEvent teamLog(String text) {
        return new RenderEvent(Type.TEAM_LOG, "team", null, text, Map.of());
    }

    public static RenderEvent error(String scope, String text) {
        return new RenderEvent(Type.ERROR, scope, null, text, Map.of());
    }

    public static RenderEvent tokenUsage(String text, Map<String, String> metadata) {
        return new RenderEvent(Type.TOKEN_USAGE, "tokens", null, text, metadata);
    }

    public RenderEvent withMetadata(String key, String value) {
        if (key == null || key.isBlank() || value == null) {
            return this;
        }
        Map<String, String> copy = new LinkedHashMap<>(metadata);
        copy.put(key, value);
        return new RenderEvent(type, scope, id, text, copy);
    }
}
