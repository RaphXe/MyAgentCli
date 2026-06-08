package com.raph.agent;

import com.raph.render.PlainRenderer;
import com.raph.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamViewTest {
    @Test
    void createsTeamSnapshotFromTaskBoardAndTranscript() {
        TaskBoard board = new TaskBoard();
        TaskBoard.TaskItem active = board.createTask("探索代码", "读取入口和渲染层", List.of());
        TaskBoard.TaskItem review = board.createTask("审查结果", "检查事件管线", List.of());
        board.claimTask(active.id(), "researcher");
        board.startTask(active.id(), "researcher");
        board.markReadyForReview(review.id(), "coder", "RenderEvent 已接入");
        AgentMessage message = AgentMessage.of("thread-1", "coder", "reviewer",
                AgentMessage.Type.REVIEW, "请审查渲染事件管线");

        TeamView view = TeamView.from(
                "产品化 TUI",
                "running",
                board.allTasks(),
                List.of(),
                List.of(message),
                "",
                8,
                2,
                3,
                32
        );

        assertEquals("产品化 TUI", view.goal());
        assertEquals("running", view.status());
        assertEquals(2, view.totalTasks());
        assertEquals(1, view.activeTasks());
        assertEquals(1, view.reviewableTasks());
        assertEquals(0, view.terminalTasks());
        assertEquals("IN_PROGRESS", view.tasks().get(0).status());
        assertEquals("REVIEW", view.recentMessages().get(0).type());
    }

    @Test
    void runtimeExposesIdleTeamViewBeforeRun() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                null,
                new ToolRegistry(),
                3,
                new PlainRenderer(new PrintStream(out, true, StandardCharsets.UTF_8))
        );

        TeamView view = runtime.currentTeamView();

        assertEquals("idle", view.status());
        assertEquals(3, view.maxRounds());
        assertEquals(0, view.agentSteps());
        assertEquals(5, view.agents().size());
        assertTrue(view.tasks().isEmpty());
    }
}
