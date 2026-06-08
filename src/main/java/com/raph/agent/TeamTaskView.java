package com.raph.agent;

import java.time.Instant;
import java.util.List;

/**
 * Immutable UI snapshot of a team task-board item.
 */
public record TeamTaskView(
        String id,
        String title,
        String description,
        String status,
        String ownerAgentId,
        List<String> dependencies,
        List<String> artifacts,
        List<String> notes,
        Instant updatedAt
) {
    public TeamTaskView {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static TeamTaskView from(TaskBoard.TaskItem task) {
        if (task == null) {
            return new TeamTaskView("", "", "", "", "", List.of(), List.of(), List.of(), null);
        }
        return new TeamTaskView(
                task.id(),
                task.title(),
                task.description(),
                task.status() == null ? "" : task.status().name(),
                task.ownerAgentId() == null ? "" : task.ownerAgentId(),
                task.dependencies(),
                task.artifacts(),
                task.notes(),
                task.updatedAt()
        );
    }
}
