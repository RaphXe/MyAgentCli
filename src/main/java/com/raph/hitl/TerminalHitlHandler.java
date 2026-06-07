package com.raph.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端 HITL 审批处理器。
 */
public class TerminalHitlHandler implements HitlHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile boolean enabled;
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();
    private final BufferedReader in;
    private final PrintStream out;

    public TerminalHitlHandler(boolean enabled) {
        this(enabled, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
    }

    TerminalHitlHandler(boolean enabled, BufferedReader in, PrintStream out) {
        this.enabled = enabled;
        this.in = in;
        this.out = out == null ? System.out : out;
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
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        if (isApprovedAllByTool(request.toolName())) {
            out.println("  [HITL] " + request.toolName() + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAll();
        }
        if (isApprovedAllByServer(mcpServer)) {
            out.println("  [HITL] MCP server " + mcpServer + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAllByServer();
        }

        out.println();
        out.println("────────── ⚠️  HITL 审批请求 ──────────");
        out.println(request.toDisplayText());
        return promptUntilDecision(request);
    }

    private ApprovalResult promptUntilDecision(ApprovalRequest request) {
        for (int attempt = 0; attempt < 5; attempt++) {
            out.println();
            if (request.workspaceApprovalRequired()) {
                if (ApprovalPolicy.requiresApproval(request.toolName())) {
                    out.println("请选择操作：[y/Enter] 批准本次  [w] 扩展工作区（仅当前会话）  [wa] 扩展工作区并全部放行本工具  [a] 全部放行危险工具  [n] 拒绝  [s] 跳过  [m] 修改参数");
                } else {
                    out.println("请选择操作：[y/Enter] 批准本次  [w] 扩展工作区（仅当前会话）  [n] 拒绝  [s] 跳过  [m] 修改参数");
                }
            } else {
                out.println("请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数");
            }
            out.print("> ");
            out.flush();

            String input;
            try {
                input = in.readLine();
            } catch (IOException e) {
                out.println("  [HITL] 读取用户输入失败，保守处理为拒绝");
                return ApprovalResult.reject("读取输入失败: " + e.getMessage());
            }
            if (input == null) {
                out.println("  [HITL] 输入流已关闭，保守处理为拒绝");
                return ApprovalResult.reject("输入流已关闭");
            }

            String normalized = input.trim().toLowerCase();
            if (normalized.isEmpty() || "y".equals(normalized)) {
                out.println("  已批准");
                return ApprovalResult.approve();
            }
            switch (normalized) {
                case "w", "workspace", "expand" -> {
                    if (request.workspaceApprovalRequired()) {
                        out.println("  已批准，并将扩展工作区（仅当前会话）: " + request.workspaceSuggestedRoot());
                        return ApprovalResult.approveExpandWorkspace();
                    }
                    out.println("  当前请求不需要扩展工作区");
                }
                case "wa", "aw", "workspace-all", "expand-all" -> {
                    if (request.workspaceApprovalRequired() && ApprovalPolicy.requiresApproval(request.toolName())) {
                        approvedAllByTool.add(request.toolName());
                        out.println("  已批准，将扩展工作区（仅当前会话）: " + request.workspaceSuggestedRoot());
                        out.println("  后续 " + request.toolName() + " 操作将在本次会话中自动通过");
                        return ApprovalResult.approveExpandWorkspaceAndAll();
                    }
                    out.println("  当前请求不同时包含工作区扩展和危险工具审批");
                }
                case "a" -> {
                    return promptApproveAllScope(request);
                }
                case "n" -> {
                    out.print("  拒绝原因（可直接回车跳过）：");
                    out.flush();
                    String reason;
                    try {
                        reason = in.readLine();
                    } catch (IOException e) {
                        reason = "";
                    }
                    return ApprovalResult.reject(reason == null ? "" : reason.trim());
                }
                case "s" -> {
                    out.println("  已跳过本次操作");
                    return ApprovalResult.skip();
                }
                case "m" -> {
                    ApprovalResult modified = promptModifiedArguments(request);
                    if (modified != null) {
                        return modified;
                    }
                }
                default -> out.println("  ❓ 无法识别的选项：'" + input + "'，请输入 y/w/wa/a/n/s/m 之一（Enter 等价于 y）");
            }
        }
        out.println("  [HITL] 连续多次无效输入，保守处理为拒绝");
        return ApprovalResult.reject("连续多次无效输入");
    }

    private ApprovalResult promptApproveAllScope(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        if (mcpServer == null || mcpServer.isBlank()) {
            approvedAllByTool.add(request.toolName());
            out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
            return ApprovalResult.approveAll();
        }

        out.println("  全部放行范围：");
        out.println("  [tool / Enter] 仅本工具 " + request.toolName());
        out.println("  [server]       整个 MCP server " + mcpServer);
        out.print("> ");
        out.flush();
        String scope;
        try {
            scope = in.readLine();
        } catch (IOException e) {
            scope = "";
        }
        String normalized = scope == null ? "" : scope.trim().toLowerCase();
        if ("server".equals(normalized) || "s".equals(normalized)) {
            approvedAllByServer.add(mcpServer);
            out.println("  已批准，后续 MCP server " + mcpServer + " 的工具调用将自动通过");
            return ApprovalResult.approveAllByServer();
        }
        approvedAllByTool.add(request.toolName());
        out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
        return ApprovalResult.approveAll();
    }

    private ApprovalResult promptModifiedArguments(ApprovalRequest request) {
        out.println("  当前参数：" + request.arguments());
        out.print("  请输入修改后的参数（JSON 格式，空行则使用原始参数）：");
        out.flush();

        String modified;
        try {
            modified = in.readLine();
        } catch (IOException e) {
            out.println("  读取失败，回到主菜单");
            return null;
        }
        if (modified == null || modified.isBlank()) {
            out.println("  输入为空，改为批准原始参数");
            return ApprovalResult.approve();
        }

        String trimmed = modified.trim();
        try {
            MAPPER.readTree(trimmed);
        } catch (Exception e) {
            out.println("  ❌ 修改后的参数不是合法 JSON：" + e.getMessage());
            return null;
        }
        return ApprovalResult.modify(trimmed);
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return serverName != null && approvedAllByServer.contains(serverName);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();
        approvedAllByServer.clear();
    }

    @Override
    public void approveAllByTool(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            approvedAllByTool.add(toolName);
        }
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        if (serverName != null) {
            approvedAllByServer.remove(serverName);
        }
    }
}
