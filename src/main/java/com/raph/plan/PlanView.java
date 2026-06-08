package com.raph.plan;

import java.util.List;

/**
 * Immutable UI-friendly snapshot of an execution plan.
 */
public record PlanView(
        String id,
        String goal,
        String summary,
        String status,
        List<PlanTaskView> tasks,
        List<String> executionOrder,
        int totalTasks,
        int completedTasks,
        int failedTasks,
        int pendingTasks,
        long startTime,
        long endTime,
        long durationMillis
) {
    public PlanView {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        executionOrder = executionOrder == null ? List.of() : List.copyOf(executionOrder);
    }

    public static PlanView from(ExecutionPlan plan) {
        if (plan == null) {
            return new PlanView("", "", "", "", List.of(), List.of(), 0, 0, 0, 0, 0, 0, 0);
        }
        List<PlanTaskView> taskViews = plan.getAllTasks().stream()
                .map(PlanTaskView::from)
                .toList();
        int completed = (int) taskViews.stream().filter(task -> "COMPLETED".equals(task.status())).count();
        int failed = (int) taskViews.stream().filter(task -> "FAILED".equals(task.status())).count();
        int pending = (int) taskViews.stream()
                .filter(task -> "PENDING".equals(task.status()) || "RUNNING".equals(task.status()))
                .count();
        long duration = durationMillis(plan.getStartTime(), plan.getEndTime());
        return new PlanView(
                plan.getId(),
                plan.getGoal(),
                plan.getSummary(),
                plan.getStatus() == null ? "" : plan.getStatus().name(),
                taskViews,
                plan.getExecutionOrder(),
                taskViews.size(),
                completed,
                failed,
                pending,
                plan.getStartTime(),
                plan.getEndTime(),
                duration
        );
    }

    private static long durationMillis(long startTime, long endTime) {
        if (startTime <= 0) {
            return 0;
        }
        long effectiveEnd = endTime > 0 ? endTime : System.currentTimeMillis();
        return Math.max(0, effectiveEnd - startTime);
    }
}
