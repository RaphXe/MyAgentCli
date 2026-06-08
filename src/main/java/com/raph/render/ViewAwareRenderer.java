package com.raph.render;

import com.raph.agent.TeamView;
import com.raph.plan.PlanView;

/**
 * Optional renderer capability for state snapshots.
 */
public interface ViewAwareRenderer {
    default void updatePlanView(PlanView view) {
    }

    default void updateTeamView(TeamView view) {
    }
}
