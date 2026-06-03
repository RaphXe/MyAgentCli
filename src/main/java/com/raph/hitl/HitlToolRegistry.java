package com.raph.hitl;

import com.raph.tool.ToolRegistry;

/**
 * 在危险工具调用前插入人工审批的工具注册表。
 */
public class HitlToolRegistry extends ToolRegistry {
    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    @Override
    public String executeTool(String toolName, String arguments) {
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(toolName)) {
            return super.executeTool(toolName, arguments);
        }

        String mcpServer = ApprovalPolicy.mcpServerName(toolName);
        if (hitlHandler.isApprovedAllByTool(toolName) || hitlHandler.isApprovedAllByServer(mcpServer)) {
            return super.executeTool(toolName, arguments);
        }

        ApprovalRequest request = ApprovalRequest.of(toolName, arguments, null);
        ApprovalResult result = hitlHandler.requestApproval(request);
        if (result.isRejected()) {
            String reason = result.reason() == null || result.reason().isBlank()
                    ? "用户拒绝了此操作"
                    : result.reason();
            return "[HITL] 操作已被拒绝：" + reason;
        }
        if (result.isSkipped()) {
            return "[HITL] 操作已被跳过";
        }
        if (!result.isApproved()) {
            return "[HITL] 操作未获批准";
        }
        return super.executeTool(toolName, result.effectiveArguments(arguments));
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
