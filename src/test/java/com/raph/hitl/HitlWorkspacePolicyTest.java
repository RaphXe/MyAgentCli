package com.raph.hitl;

import com.raph.llm.LlmClient;
import com.raph.tool.ToolRegistry;
import com.raph.tool.WorkspacePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitlWorkspacePolicyTest {

    @TempDir
    Path outsideDir;

    @Test
    void plainToolRegistryRejectsPathsOutsideWorkspace() throws Exception {
        Path workspace = outsideDir.resolve("workspace");
        Path outside = outsideDir.resolve("outside.txt");
        Files.createDirectories(workspace);
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);

        ToolRegistry registry = new ToolRegistry(new WorkspacePolicy(workspace));

        String result = registry.executeTool("read_file", "{\"path\":\"" + outside + "\"}");

        assertTrue(result.contains("工作区访问被拒绝"), result);
        assertTrue(result.contains(outside.toString()), result);
    }

    @Test
    void approvalAllowsOneOutsideWorkspaceAccessOnly() throws Exception {
        Path outside = outsideDir.resolve("once.txt");
        Files.writeString(outside, "read once", StandardCharsets.UTF_8);
        QueueHitlHandler handler = new QueueHitlHandler(false,
                ApprovalResult.approve(),
                ApprovalResult.reject("second read denied"));
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        String first = registry.executeTool("read_file", "{\"path\":\"" + outside + "\"}");
        String second = registry.executeTool("read_file", "{\"path\":\"" + outside + "\"}");

        assertTrue(first.contains("read once"), first);
        assertTrue(second.contains("second read denied"), second);
        assertEquals(2, handler.requestCount);
    }

    @Test
    void approvalCanExpandWorkspaceForFutureAccesses() throws Exception {
        Path firstFile = outsideDir.resolve("first.txt");
        Path secondFile = outsideDir.resolve("second.txt");
        Files.writeString(firstFile, "first", StandardCharsets.UTF_8);
        Files.writeString(secondFile, "second", StandardCharsets.UTF_8);
        QueueHitlHandler handler = new QueueHitlHandler(false, ApprovalResult.approveExpandWorkspace());
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        String first = registry.executeTool("read_file", "{\"path\":\"" + firstFile + "\"}");
        String second = registry.executeTool("read_file", "{\"path\":\"" + secondFile + "\"}");

        assertTrue(first.contains("first"), first);
        assertTrue(second.contains("second"), second);
        assertEquals(1, handler.requestCount);
    }

    @Test
    void clearSessionStateResetsExpandedWorkspace() throws Exception {
        Path outside = outsideDir.resolve("reset.txt");
        Files.writeString(outside, "reset me", StandardCharsets.UTF_8);
        QueueHitlHandler handler = new QueueHitlHandler(false,
                ApprovalResult.approveExpandWorkspace(),
                ApprovalResult.reject("workspace reset"));
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        String first = registry.executeTool("read_file", "{\"path\":\"" + outside + "\"}");
        registry.clearSessionState();
        String second = registry.executeTool("read_file", "{\"path\":\"" + outside + "\"}");

        assertTrue(first.contains("reset me"), first);
        assertTrue(second.contains("workspace reset"), second);
        assertEquals(2, handler.requestCount);
    }

    @Test
    void clearApprovedAllResetsDangerousToolApprovalCache() throws Exception {
        Path outside = outsideDir.resolve("approved-all.txt");
        QueueHitlHandler handler = new QueueHitlHandler(true,
                ApprovalResult.approveAll(),
                ApprovalResult.reject("approval cache reset"));
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        String first = registry.executeTool("write_file",
                "{\"path\":\"" + outside + "\",\"content\":\"first\",\"mode\":\"overwrite\"}");
        handler.clearApprovedAll();
        String second = registry.executeTool("write_file",
                "{\"path\":\"" + outside + "\",\"content\":\"second\",\"mode\":\"overwrite\"}");

        assertTrue(first.contains("文件已覆盖写入"), first);
        assertTrue(second.contains("approval cache reset"), second);
        assertEquals(2, handler.requestCount);
    }

    @Test
    void dangerousToolAndWorkspaceExpansionShareOneApproval() throws Exception {
        Path outside = outsideDir.resolve("write.txt");
        QueueHitlHandler handler = new QueueHitlHandler(true, ApprovalResult.approveExpandWorkspace());
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + outside + "\",\"content\":\"written\",\"mode\":\"overwrite\"}");

        assertTrue(result.contains("文件已覆盖写入"), result);
        assertTrue(Files.readString(outside).contains("written"));
        assertEquals(1, handler.requestCount);
        assertEquals("write_file", handler.lastRequest.toolName());
        assertTrue(handler.lastRequest.workspaceApprovalRequired());
        assertFalse(handler.lastRequest.workspaceSuggestedRoot().isBlank());
    }

    @Test
    void executeCommandAbsolutePathOutsideWorkspaceSharesDangerApproval() throws Exception {
        Path outside = outsideDir.resolve("command-dir");
        Files.createDirectories(outside);
        QueueHitlHandler handler = new QueueHitlHandler(true, ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        String result = registry.executeTool("execute_command",
                "{\"command\":\"ls " + outside + "\"}");

        assertTrue(result.contains("命令执行完成"), result);
        assertEquals(1, handler.requestCount);
        assertEquals("execute_command", handler.lastRequest.toolName());
        assertTrue(handler.lastRequest.workspaceApprovalRequired());
        assertTrue(handler.lastRequest.workspacePath().contains(outside.toString()));
    }

    @Test
    void approveWorkspaceAndAllExpandsWorkspaceAndApprovesFutureDangerousToolCalls() throws Exception {
        Path outside = outsideDir.resolve("wa-dir");
        Files.createDirectories(outside);
        QueueHitlHandler handler = new QueueHitlHandler(true, ApprovalResult.approveExpandWorkspaceAndAll());
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        String first = registry.executeTool("execute_command",
                "{\"command\":\"ls " + outside + "\"}");
        String second = registry.executeTool("execute_command",
                "{\"command\":\"ls " + outside + "\"}");

        assertTrue(first.contains("命令执行完成"), first);
        assertTrue(second.contains("命令执行完成"), second);
        assertEquals(1, handler.requestCount);
        assertTrue(handler.isApprovedAllByTool("execute_command"));
    }

    @Test
    void concurrentToolsForSameWorkspaceExpansionAskOnce() throws Exception {
        Path outside = outsideDir.resolve("shared-workspace");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("README.md"), "hello", StandardCharsets.UTF_8);
        QueueHitlHandler handler = new QueueHitlHandler(false, ApprovalResult.approveExpandWorkspace());
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                toolCall("list-1", "list_dir", "{\"path\":\"" + outside + "\"}"),
                toolCall("tree-1", "project_tree", "{\"path\":\"" + outside + "\",\"max_depth\":\"2\"}")
        ));

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> !result.result().contains("工作区访问被拒绝")), results.toString());
        assertEquals(1, handler.requestCount);
        assertTrue(handler.lastRequest.workspaceApprovalRequired());
    }

    private static LlmClient.ToolCall toolCall(String id, String name, String arguments) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, arguments));
    }

    private static final class QueueHitlHandler implements HitlHandler {
        private final Queue<ApprovalResult> results = new ArrayDeque<>();
        private final java.util.Set<String> approvedTools = new java.util.HashSet<>();
        private boolean enabled;
        private int requestCount;
        private ApprovalRequest lastRequest;

        private QueueHitlHandler(boolean enabled, ApprovalResult... results) {
            this.enabled = enabled;
            this.results.addAll(java.util.List.of(results));
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requestCount++;
            lastRequest = request;
            ApprovalResult result = results.isEmpty() ? ApprovalResult.reject("no queued result") : results.remove();
            if (result.decision() == ApprovalResult.Decision.APPROVED_ALL) {
                approvedTools.add(request.toolName());
            }
            return result;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isApprovedAllByTool(String toolName) {
            return approvedTools.contains(toolName);
        }

        @Override
        public void approveAllByTool(String toolName) {
            approvedTools.add(toolName);
        }

        @Override
        public void clearApprovedAll() {
            approvedTools.clear();
        }
    }
}
