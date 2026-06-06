package com.raph.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 共享任务板。它把协作状态从聊天文本中抽出来，供 Runtime 和 Agent 共同观察。
 */
public class TaskBoard {
    private final Map<String, TaskItem> tasks = new LinkedHashMap<>();
    private int nextId = 1;

    public enum TaskStatus {
        TODO,
        CLAIMED,
        IN_PROGRESS,
        BLOCKED,
        READY_FOR_REVIEW,
        APPROVED,
        REJECTED,
        DONE,
        CANCELLED
    }

    public record TaskItem(
            String id,
            String title,
            String description,
            TaskStatus status,
            String ownerAgentId,
            List<String> dependencies,
            List<String> artifacts,
            List<String> notes,
            Instant updatedAt
    ) {
        public TaskItem {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            notes = notes == null ? List.of() : List.copyOf(notes);
            updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        }
    }

    public synchronized TaskItem createTask(String title, String description, List<String> dependencies) {
        String id = "task_" + nextId++;
        TaskItem task = new TaskItem(
                id,
                blankToDefault(title, id),
                description == null ? "" : description,
                TaskStatus.TODO,
                null,
                dependencies,
                List.of(),
                List.of(),
                Instant.now()
        );
        tasks.put(id, task);
        return task;
    }

    public synchronized boolean claimTask(String taskId, String agentId) {
        TaskItem task = tasks.get(taskId);
        if (task == null || agentId == null || agentId.isBlank()) return false;
        if (task.status() != TaskStatus.TODO && task.status() != TaskStatus.REJECTED) return false;
        if (!dependenciesDone(task)) return false;
        tasks.put(taskId, copy(task, TaskStatus.CLAIMED, agentId, task.artifacts(), task.notes()));
        return true;
    }

    public synchronized boolean startTask(String taskId, String agentId) {
        TaskItem task = tasks.get(taskId);
        if (task == null || !agentId.equals(task.ownerAgentId())) return false;
        if (task.status() != TaskStatus.CLAIMED && task.status() != TaskStatus.REJECTED) return false;
        if (!dependenciesDone(task)) return false;
        tasks.put(taskId, copy(task, TaskStatus.IN_PROGRESS, agentId, task.artifacts(), task.notes()));
        return true;
    }

    public synchronized boolean blockTask(String taskId, String agentId, String reason) {
        TaskItem task = tasks.get(taskId);
        if (task == null || !agentId.equals(task.ownerAgentId())) return false;
        tasks.put(taskId, copy(task, TaskStatus.BLOCKED, agentId, task.artifacts(), append(task.notes(), note(agentId, reason))));
        return true;
    }

    public synchronized boolean markReadyForReview(String taskId, String agentId, String artifact) {
        TaskItem task = tasks.get(taskId);
        if (task == null || agentId == null || agentId.isBlank()) return false;
        if (task.ownerAgentId() != null && !agentId.equals(task.ownerAgentId())) return false;
        if (task.status() != TaskStatus.IN_PROGRESS
                && task.status() != TaskStatus.CLAIMED
                && task.status() != TaskStatus.REJECTED
                && !(task.ownerAgentId() == null && task.status() == TaskStatus.TODO && dependenciesDone(task))) {
            return false;
        }
        List<String> artifacts = appendIfPresent(task.artifacts(), artifact);
        tasks.put(taskId, copy(task, TaskStatus.READY_FOR_REVIEW, agentId, artifacts, task.notes()));
        return true;
    }

    public synchronized boolean approveTask(String taskId, String reviewerId, String note) {
        TaskItem task = tasks.get(taskId);
        if (task == null || task.status() != TaskStatus.READY_FOR_REVIEW) return false;
        tasks.put(taskId, copy(task, TaskStatus.APPROVED, task.ownerAgentId(), task.artifacts(), appendIfPresent(task.notes(), note(reviewerId, note))));
        return true;
    }

    public synchronized boolean rejectTask(String taskId, String reviewerId, String reason) {
        TaskItem task = tasks.get(taskId);
        if (task == null || task.status() != TaskStatus.READY_FOR_REVIEW) return false;
        tasks.put(taskId, copy(task, TaskStatus.REJECTED, task.ownerAgentId(), task.artifacts(), appendIfPresent(task.notes(), note(reviewerId, reason))));
        return true;
    }

    public synchronized boolean completeTask(String taskId, String agentId) {
        TaskItem task = tasks.get(taskId);
        if (task == null) return false;
        if (task.ownerAgentId() != null && !task.ownerAgentId().equals(agentId)) return false;
        if (task.status() != TaskStatus.APPROVED && task.status() != TaskStatus.IN_PROGRESS && task.status() != TaskStatus.READY_FOR_REVIEW) {
            return false;
        }
        tasks.put(taskId, copy(task, TaskStatus.DONE, task.ownerAgentId(), task.artifacts(), task.notes()));
        return true;
    }

    public synchronized boolean cancelTask(String taskId, String agentId, String reason) {
        TaskItem task = tasks.get(taskId);
        if (task == null) return false;
        if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) return false;
        tasks.put(taskId, copy(task, TaskStatus.CANCELLED, task.ownerAgentId(), task.artifacts(),
                appendIfPresent(task.notes(), note(agentId, reason))));
        return true;
    }

    public synchronized List<TaskItem> readyTasks() {
        return tasks.values().stream()
                .filter(task -> task.status() == TaskStatus.TODO || task.status() == TaskStatus.REJECTED)
                .filter(this::dependenciesDone)
                .toList();
    }

    public synchronized List<TaskItem> reviewableTasks() {
        return tasks.values().stream()
                .filter(task -> task.status() == TaskStatus.READY_FOR_REVIEW)
                .toList();
    }

    public synchronized List<TaskItem> activeTasksFor(String agentId) {
        if (agentId == null || agentId.isBlank()) return List.of();
        return tasks.values().stream()
                .filter(task -> agentId.equals(task.ownerAgentId()))
                .filter(this::dependenciesDone)
                .filter(task -> task.status() == TaskStatus.CLAIMED
                        || task.status() == TaskStatus.IN_PROGRESS
                        || task.status() == TaskStatus.REJECTED)
                .toList();
    }

    public synchronized boolean hasActiveTaskFor(String agentId) {
        return !activeTasksFor(agentId).isEmpty();
    }

    public synchronized List<TaskItem> allTasks() {
        return List.copyOf(tasks.values());
    }

    public synchronized TaskItem get(String taskId) {
        return tasks.get(taskId);
    }

    public synchronized boolean isEmpty() {
        return tasks.isEmpty();
    }

    public synchronized boolean allTerminal() {
        return !tasks.isEmpty() && tasks.values().stream().allMatch(task ->
                task.status() == TaskStatus.DONE
                        || task.status() == TaskStatus.APPROVED
                        || task.status() == TaskStatus.CANCELLED);
    }

    public synchronized boolean hasActiveTaskOwnedBy(String agentId) {
        return hasActiveTaskFor(agentId);
    }

    public synchronized String renderForPrompt() {
        if (tasks.isEmpty()) return "任务板为空。";
        StringBuilder sb = new StringBuilder();
        for (TaskItem task : tasks.values()) {
            sb.append("- ").append(task.id()).append(" [").append(task.status()).append("] ")
                    .append(task.title()).append(" owner=").append(task.ownerAgentId() == null ? "none" : task.ownerAgentId())
                    .append(" deps=").append(task.dependencies().isEmpty() ? "[]" : task.dependencies())
                    .append("\n  ").append(task.description()).append("\n");
            if (!task.artifacts().isEmpty()) sb.append("  artifacts: ").append(task.artifacts()).append("\n");
            if (!task.notes().isEmpty()) sb.append("  notes: ").append(task.notes()).append("\n");
        }
        return sb.toString();
    }

    public synchronized String renderSummary() {
        if (tasks.isEmpty()) return "暂无任务。";
        StringBuilder sb = new StringBuilder();
        for (TaskItem task : tasks.values()) {
            sb.append(task.id()).append(" [").append(task.status()).append("] ")
                    .append(task.title()).append("\n");
        }
        return sb.toString();
    }

    private boolean dependenciesDone(TaskItem task) {
        for (String depId : task.dependencies()) {
            TaskItem dep = tasks.get(depId);
            if (dep == null || (dep.status() != TaskStatus.DONE && dep.status() != TaskStatus.APPROVED)) {
                return false;
            }
        }
        return true;
    }

    private static TaskItem copy(TaskItem task, TaskStatus status, String ownerAgentId,
                                 List<String> artifacts, List<String> notes) {
        return new TaskItem(task.id(), task.title(), task.description(), status, ownerAgentId,
                task.dependencies(), artifacts, notes, Instant.now());
    }

    private static List<String> append(List<String> original, String value) {
        List<String> copy = new ArrayList<>(original == null ? List.of() : original);
        copy.add(value == null ? "" : value);
        return List.copyOf(copy);
    }

    private static List<String> appendIfPresent(List<String> original, String value) {
        if (value == null || value.isBlank()) return original == null ? List.of() : List.copyOf(original);
        return append(original, value);
    }

    private static String note(String author, String text) {
        String prefix = author == null || author.isBlank() ? "system" : author;
        return prefix + ": " + (text == null ? "" : text);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
