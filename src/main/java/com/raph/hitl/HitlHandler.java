package com.raph.hitl;

/**
 * HITL 审批交互接口。
 */
public interface HitlHandler {
    ApprovalResult requestApproval(ApprovalRequest request);

    boolean isEnabled();

    void setEnabled(boolean enabled);

    default boolean isApprovedAllByTool(String toolName) {
        return false;
    }

    default boolean isApprovedAllByServer(String serverName) {
        return false;
    }

    default void clearApprovedAll() {
    }

    default void approveAllByTool(String toolName) {
    }

    default void clearApprovedAllForServer(String serverName) {
    }
}
