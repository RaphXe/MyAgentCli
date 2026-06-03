package com.raph.agent;

import com.raph.llm.LlmClient;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 有 inbox、有角色提示词、可基于共享 TaskBoard 自主输出 actions 的团队 Agent。
 */
public class TeamAgent {
    private static final int DEFAULT_TOOL_ITERATIONS = 4;
    private static final int EXPLORATION_TOOL_ITERATIONS = 8;

    private final String id;
    private final AgentRole role;
    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> history = new ArrayList<>();

    public TeamAgent(String id, AgentRole role, LlmClient client, ToolRegistry toolRegistry) {
        this.id = id;
        this.role = role;
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.history.add(LlmClient.Message.system(systemPrompt()));
    }

    public String id() {
        return id;
    }

    public AgentRole role() {
        return role;
    }

    public AgentDecision step(AgentRuntime.RuntimeView view, List<AgentMessage> inbox) throws IOException {
        return step(view, inbox, LlmClient.StreamListener.NO_OP);
    }

    public AgentDecision step(AgentRuntime.RuntimeView view, List<AgentMessage> inbox,
                              LlmClient.StreamListener listener) throws IOException {
        LlmClient.StreamListener streamListener = listener == null ? LlmClient.StreamListener.NO_OP : listener;
        String prompt = buildTurnPrompt(view, inbox);
        history.add(LlmClient.Message.user(prompt));

        int iteration = 0;
        while (iteration++ < maxToolIterations()) {
            LlmClient.ChatResponse response = client.chat(
                    history,
                    canUseTools() ? toolRegistry.getToolDefinitions() : null,
                    streamListener
            );
            if (response.hasToolCalls()) {
                history.add(LlmClient.Message.assistant(response.content(), response.toolCalls()));
                for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                    String result = toolRegistry.executeTool(toolCall.function().name(), toolCall.function().arguments());
                    history.add(LlmClient.Message.tool(toolCall.id(), result));
                }
                continue;
            }
            history.add(LlmClient.Message.assistant(response.content(), null));
            return AgentDecision.parse(response.content());
        }

        return AgentDecision.fallback("达到工具调用轮数上限(" + maxToolIterations() + ")，已暂停并等待下一轮。 ");
    }

    private int maxToolIterations() {
        return switch (role) {
            case Researcher, Coder -> EXPLORATION_TOOL_ITERATIONS;
            default -> DEFAULT_TOOL_ITERATIONS;
        };
    }

    private boolean canUseTools() {
        return role == AgentRole.Researcher || role == AgentRole.Coder;
    }

    private String buildTurnPrompt(AgentRuntime.RuntimeView view, List<AgentMessage> inbox) {
        StringBuilder sb = new StringBuilder();
        sb.append("团队目标：\n").append(view.goal()).append("\n\n");
        sb.append("你的身份：").append(id).append(" / ").append(role).append(" - ").append(role.getDescription()).append("\n\n");
        sb.append("当前任务板：\n").append(view.taskBoard()).append("\n");
        sb.append("你的新消息：\n");
        if (inbox.isEmpty()) {
            sb.append("无直接消息。请根据任务板判断是否需要主动行动。\n");
        } else {
            for (AgentMessage message : inbox) {
                sb.append("- from=").append(message.senderId())
                        .append(" type=").append(message.type())
                        .append(" content=").append(message.content()).append("\n");
            }
        }
        sb.append("\n最近团队 transcript：\n").append(view.recentTranscript()).append("\n");
        sb.append("\n请只输出 JSON 决策，不要输出 Markdown。\n");
        return sb.toString();
    }

    private String systemPrompt() {
        return """
                你是一个自治 Multi-Agent 团队成员。你不会等待中心编排器分配每一步，而是根据 inbox、TaskBoard 和团队 transcript 主动决策。

                通用规则：
                1. 所有回复必须是 JSON，格式为 {"status":"working|idle|blocked|done","actions":[],"final_answer":null}。
                2. actions 支持：
                   - send_message: {"type":"send_message","to":"agentId或*","message_type":"REQUEST_HELP|DELEGATE|REPORT_PROGRESS|REVIEW|APPROVE|REJECT|FINAL","content":"..."}
                   - create_task: {"type":"create_task","title":"...","description":"...","dependencies":["task_1"]}
                   - claim_task: {"type":"claim_task","task_id":"task_1"}
                   - start_task: {"type":"start_task","task_id":"task_1"}
                   - ready_for_review: {"type":"ready_for_review","task_id":"task_1","artifact":"产出摘要"}
                   - approve_task: {"type":"approve_task","task_id":"task_1","note":"审查意见"}
                   - reject_task: {"type":"reject_task","task_id":"task_1","reason":"打回原因"}
                   - complete_task: {"type":"complete_task","task_id":"task_1"}
                   - cancel_task: {"type":"cancel_task","task_id":"task_1","reason":"取消原因"}
                   - block_task: {"type":"block_task","task_id":"task_1","reason":"阻塞原因"}
                   - final_answer: {"type":"final_answer","content":"最终答复"}
                3. 不要重复认领已有 owner 的任务。依赖未完成时，不要开始任务。
                4. 不要向正在 IN_PROGRESS/CLAIMED 的任务 owner 反复询问“进展如何”；运行时会显示心跳。若确实阻塞，请改为 cancel_task、重新委派或等待审查结果。
                5. 你可以通过消息请求其他 Agent 协助、质疑方案或要求审查。
                6. 若你是 Coordinator，优先创建少量必要任务、取消重复/过期任务，并尽早判断是否可以最终收尾。
                7. 当前运行目录就是用户项目根目录；分析“当前项目”时，直接创建探索任务并使用 list_dir/read_file，不要反复向 user 索要路径或授权。
                8. 若你是 Coder/Researcher，看到适合自己的 TODO/REJECTED 任务时可以主动 claim/start，并在必要时调用工具。
                9. 若你是 Tester，默认只做独立审查和一致性检查，不主动扫描全项目，除非 coordinator 明确要求。
                10. 若你是 Reviewer，看到 READY_FOR_REVIEW 任务时主动审查并 approve/reject；APPROVED 即视为可交付，不必要求 owner 再 complete_task。
                """;
    }
}
