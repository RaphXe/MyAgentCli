package com.raph.agent;

import java.util.List;

/**
 * Immutable UI-friendly snapshot of the multi-agent runtime.
 */
public record TeamView(
        String goal,
        String status,
        List<TeamTaskView> tasks,
        List<TeamAgentView> agents,
        List<TeamMessageView> recentMessages,
        String finalAnswer,
        int maxRounds,
        int currentRound,
        int agentSteps,
        int maxAgentSteps,
        int totalTasks,
        int activeTasks,
        int reviewableTasks,
        int terminalTasks
) {
    public TeamView {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        agents = agents == null ? List.of() : List.copyOf(agents);
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
    }

    public static TeamView from(String goal,
                                String status,
                                List<TaskBoard.TaskItem> taskItems,
                                List<TeamAgent> agents,
                                List<AgentMessage> recentMessages,
                                String finalAnswer,
                                int maxRounds,
                                int currentRound,
                                int agentSteps,
                                int maxAgentSteps) {
        List<TeamTaskView> taskViews = taskItems == null ? List.of()
                : taskItems.stream().map(TeamTaskView::from).toList();
        int active = (int) taskViews.stream()
                .filter(task -> task.status().equals("CLAIMED")
                        || task.status().equals("IN_PROGRESS")
                        || task.status().equals("BLOCKED")
                        || task.status().equals("REJECTED"))
                .count();
        int reviewable = (int) taskViews.stream()
                .filter(task -> task.status().equals("READY_FOR_REVIEW"))
                .count();
        int terminal = (int) taskViews.stream()
                .filter(task -> task.status().equals("DONE")
                        || task.status().equals("APPROVED")
                        || task.status().equals("CANCELLED"))
                .count();
        return new TeamView(
                goal == null ? "" : goal,
                status == null ? "" : status,
                taskViews,
                agents == null ? List.of() : agents.stream().map(TeamAgentView::from).toList(),
                recentMessages == null ? List.of() : recentMessages.stream().map(TeamMessageView::from).toList(),
                finalAnswer == null ? "" : finalAnswer,
                maxRounds,
                currentRound,
                agentSteps,
                maxAgentSteps,
                taskViews.size(),
                active,
                reviewable,
                terminal
        );
    }
}
