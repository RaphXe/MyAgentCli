package com.raph.plan;

import java.util.List;

/**
 * Immutable UI-friendly snapshot of a plan task.
 */
public record PlanTaskView(
        String id,
        String description,
        String type,
        String status,
        List<String> dependencies,
        List<String> dependents,
        String resultSummary,
        String error,
        long startTime,
        long endTime,
        long durationMillis
) {
    private static final int SUMMARY_LIMIT = 240;

    public PlanTaskView {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        dependents = dependents == null ? List.of() : List.copyOf(dependents);
    }

    public static PlanTaskView from(Task task) {
        if (task == null) {
            return new PlanTaskView("", "", "", "", List.of(), List.of(), "", "", 0, 0, 0);
        }
        return new PlanTaskView(
                task.getId(),
                task.getDescription(),
                task.getType() == null ? "" : task.getType().name(),
                task.getStatus() == null ? "" : task.getStatus().name(),
                task.getDependencies(),
                task.getDependents(),
                summarize(task.getResult()),
                task.getError() == null ? "" : task.getError(),
                task.getStartTime(),
                task.getEndTime(),
                durationMillis(task.getStartTime(), task.getEndTime())
        );
    }

    private static String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SUMMARY_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, SUMMARY_LIMIT) + "...";
    }

    private static long durationMillis(long startTime, long endTime) {
        if (startTime <= 0) {
            return 0;
        }
        long effectiveEnd = endTime > 0 ? endTime : System.currentTimeMillis();
        return Math.max(0, effectiveEnd - startTime);
    }
}
