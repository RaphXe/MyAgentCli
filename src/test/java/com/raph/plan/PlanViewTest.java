package com.raph.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanViewTest {
    @Test
    void createsStablePlanSnapshotWithCountsAndTaskSummaries() {
        ExecutionPlan plan = new ExecutionPlan("plan-1", "优化渲染管线");
        plan.setSummary("拆分状态和渲染");
        Task completed = new Task("T1", "读取现有渲染代码", Task.TaskType.FILE_READ);
        completed.markStarted();
        completed.markCompleted("读取 Renderer 和 PlainRenderer 的代码并整理出事件入口。");
        Task failed = new Task("T2", "实现 TUI 面板", Task.TaskType.FILE_WRITE, List.of("T1"));
        failed.markStarted();
        failed.markFailed("依赖尚未完成");
        Task pending = new Task("T3", "补测试", Task.TaskType.VERIFICATION, List.of("T2"));
        plan.addTask(completed);
        plan.addTask(failed);
        plan.addTask(pending);
        plan.computeExecutionOrder();

        PlanView view = PlanView.from(plan);

        assertEquals("plan-1", view.id());
        assertEquals("优化渲染管线", view.goal());
        assertEquals(3, view.totalTasks());
        assertEquals(1, view.completedTasks());
        assertEquals(1, view.failedTasks());
        assertEquals(1, view.pendingTasks());
        assertEquals(List.of("T1"), view.tasks().get(1).dependencies());
        assertTrue(view.tasks().get(0).resultSummary().contains("Renderer"));
    }
}
