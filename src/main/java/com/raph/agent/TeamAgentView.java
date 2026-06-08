package com.raph.agent;

/**
 * Immutable UI snapshot of a team agent.
 */
public record TeamAgentView(
        String id,
        String role,
        String description,
        int contextTokens,
        int lastOutputTokens
) {
    public static TeamAgentView from(TeamAgent agent) {
        if (agent == null) {
            return new TeamAgentView("", "", "", 0, 0);
        }
        AgentRole role = agent.role();
        return new TeamAgentView(
                agent.id(),
                role == null ? "" : role.name(),
                role == null ? "" : role.getDescription(),
                agent.getContextTokens(),
                agent.getLastOutputTokens()
        );
    }
}
