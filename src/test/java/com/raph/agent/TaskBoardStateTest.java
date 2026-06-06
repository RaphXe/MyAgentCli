package com.raph.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskBoardStateTest {

    @Test
    void approvedTaskSatisfiesDependencies() {
        TaskBoard board = new TaskBoard();
        TaskBoard.TaskItem dependency = board.createTask("分析组织架构", "提交分析报告", List.of());
        TaskBoard.TaskItem next = board.createTask("提出优化方向", "基于已批准分析提出建议", List.of(dependency.id()));

        assertTrue(board.claimTask(dependency.id(), "researcher"));
        assertTrue(board.startTask(dependency.id(), "researcher"));
        assertTrue(board.markReadyForReview(dependency.id(), "researcher", "分析报告"));
        assertTrue(board.approveTask(dependency.id(), "reviewer", "通过"));

        assertTrue(board.claimTask(next.id(), "researcher"));
    }

    @Test
    void unownedReadyTaskCanBeSubmittedForReviewWithArtifact() {
        TaskBoard board = new TaskBoard();
        TaskBoard.TaskItem task = board.createTask("提出优化方向", "基于已有分析提交优化建议", List.of());

        assertTrue(board.markReadyForReview(task.id(), "researcher", "优化建议报告"));

        TaskBoard.TaskItem updated = board.get(task.id());
        assertEquals(TaskBoard.TaskStatus.READY_FOR_REVIEW, updated.status());
        assertEquals("researcher", updated.ownerAgentId());
        assertTrue(updated.artifacts().contains("优化建议报告"));
    }
}
