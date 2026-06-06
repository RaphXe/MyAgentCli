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

    @Test
    void claimedTaskCanBeCompletedByOwnerWithoutSeparateStart() {
        TaskBoard board = new TaskBoard();
        TaskBoard.TaskItem task = board.createTask("探索目录现状", "查看目录是否存在", List.of());

        assertTrue(board.claimTask(task.id(), "researcher"));
        assertTrue(board.completeTask(task.id(), "researcher"));

        TaskBoard.TaskItem updated = board.get(task.id());
        assertEquals(TaskBoard.TaskStatus.DONE, updated.status());
        assertEquals("researcher", updated.ownerAgentId());
    }

    @Test
    void unownedReadyTaskCanBeCompletedAndAssignedToCompleter() {
        TaskBoard board = new TaskBoard();
        TaskBoard.TaskItem dependency = board.createTask("创建 pom.xml", "创建基础文件", List.of());
        TaskBoard.TaskItem next = board.createTask("实现演示类", "实现多个源码文件", List.of(dependency.id()));

        assertTrue(board.completeTask(dependency.id(), "coder"));
        assertTrue(board.completeTask(next.id(), "coder"));

        TaskBoard.TaskItem updated = board.get(next.id());
        assertEquals(TaskBoard.TaskStatus.DONE, updated.status());
        assertEquals("coder", updated.ownerAgentId());
    }
}
