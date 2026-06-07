package com.raph.hitl;

import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.util.Objects;

/**
 * 在危险工具调用前插入人工审批的工具注册表。
 */
public class HitlToolRegistry extends ToolRegistry {
    private final HitlHandler hitlHandler;
    private final Object approvalLock = new Object();

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    @Override
    public String executeTool(String toolName, String arguments) {
        return super.executeTool(toolName, arguments);
    }

    @Override
    protected String executeTool(String toolName, String arguments, long toolCallingId, boolean batchReservationHeld) {
        ParsedArguments parsed;
        try {
            parsed = parseArguments(arguments);
        } catch (IOException e) {
            return "工具参数解析失败: " + e.getMessage();
        }

        WorkspaceAccess workspaceAccess = workspaceAccess(toolName, parsed.stringArgs());
        boolean needsWorkspaceApproval = workspaceAccess.requiresApproval()
                && !workspacePolicy().isInsideWorkspace(workspaceAccess.targetPath());
        boolean needsDangerApproval = hitlHandler.isEnabled()
                && ApprovalPolicy.requiresApproval(toolName)
                && !isAlreadyApprovedDangerousTool(toolName);

        if (!needsWorkspaceApproval && !needsDangerApproval) {
            return super.executeTool(toolName, arguments, toolCallingId, batchReservationHeld);
        }

        ApprovalOutcome outcome = requestApprovalOnce(toolName, arguments, workspaceAccess);
        if (outcome.message() != null) {
            return outcome.message();
        }
        return super.executeTool(toolName, outcome.arguments(), toolCallingId, batchReservationHeld);
    }

    private ApprovalOutcome requestApprovalOnce(String toolName, String arguments, WorkspaceAccess initialWorkspaceAccess) {
        synchronized (approvalLock) {
            WorkspaceAccess workspaceAccess = initialWorkspaceAccess;
            boolean needsWorkspaceApproval = workspaceAccess.requiresApproval()
                    && !workspacePolicy().isInsideWorkspace(workspaceAccess.targetPath());
            boolean needsDangerApproval = hitlHandler.isEnabled()
                    && ApprovalPolicy.requiresApproval(toolName)
                    && !isAlreadyApprovedDangerousTool(toolName);

            if (!needsWorkspaceApproval && !needsDangerApproval) {
                return ApprovalOutcome.approved(arguments);
            }

            ApprovalRequest request = ApprovalRequest.of(toolName, arguments, null);
            if (needsWorkspaceApproval) {
                request = request.withWorkspaceAccess(
                        workspaceAccess.targetPath().toString(),
                        workspaceAccess.suggestedRoot().toString()
                );
            }
            ApprovalResult result = hitlHandler.requestApproval(request);
            if (result.isRejected()) {
                String reason = result.reason() == null || result.reason().isBlank()
                        ? "用户拒绝了此操作"
                        : result.reason();
                return ApprovalOutcome.denied("[HITL] 操作已被拒绝：" + reason);
            }
            if (result.isSkipped()) {
                return ApprovalOutcome.denied("[HITL] 操作已被跳过");
            }
            if (!result.isApproved()) {
                return ApprovalOutcome.denied("[HITL] 操作未获批准");
            }

            String effectiveArguments = result.effectiveArguments(arguments);
            WorkspaceAccess effectiveWorkspaceAccess = workspaceAccess;
            if (!Objects.equals(effectiveArguments, arguments)) {
                try {
                    effectiveWorkspaceAccess = workspaceAccess(toolName, parseArguments(effectiveArguments).stringArgs());
                } catch (IOException e) {
                    return ApprovalOutcome.denied("工具参数解析失败: " + e.getMessage());
                }
            }
            if (effectiveWorkspaceAccess.requiresApproval()
                    && !workspacePolicy().isInsideWorkspace(effectiveWorkspaceAccess.targetPath())) {
                if (result.expandsWorkspace()) {
                    workspacePolicy().expand(effectiveWorkspaceAccess.suggestedRoot());
                } else {
                    workspacePolicy().allowOnce(effectiveWorkspaceAccess.targetPath());
                }
            }
            if (result.approvesAllByTool()) {
                hitlHandler.approveAllByTool(toolName);
            }
            return ApprovalOutcome.approved(effectiveArguments);
        }
    }

    private boolean isAlreadyApprovedDangerousTool(String toolName) {
        String mcpServer = ApprovalPolicy.mcpServerName(toolName);
        if (hitlHandler.isApprovedAllByTool(toolName)) {
            return true;
        }
        return hitlHandler.isApprovedAllByServer(mcpServer);
    }


    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }

    private record ApprovalOutcome(String arguments, String message) {
        private static ApprovalOutcome approved(String arguments) {
            return new ApprovalOutcome(arguments, null);
        }

        private static ApprovalOutcome denied(String message) {
            return new ApprovalOutcome(null, message);
        }
    }
}
