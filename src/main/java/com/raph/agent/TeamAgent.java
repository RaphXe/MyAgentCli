package com.raph.agent;

import com.raph.llm.LlmClient;
import com.raph.skill.SkillPrompts;
import com.raph.skill.ToolSkillResolver;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final Set<Integer> injectedToolSkillPrompts = new HashSet<>();
    private int lastInputTokens;
    private int lastOutputTokens;

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
        return step(view, inbox, listener, StepObserver.NO_OP);
    }

    public AgentDecision step(AgentRuntime.RuntimeView view, List<AgentMessage> inbox,
                              LlmClient.StreamListener listener, StepObserver observer) throws IOException {
        LlmClient.StreamListener streamListener = listener == null ? LlmClient.StreamListener.NO_OP : listener;
        StepObserver stepObserver = observer == null ? StepObserver.NO_OP : observer;
        String prompt = buildTurnPrompt(view, inbox);
        history.add(LlmClient.Message.user(prompt));

        int iteration = 0;
        while (iteration++ < maxToolIterations()) {
            LlmClient.ChatResponse response = client.chat(
                    history,
                    toolsForRole(),
                    streamListener
            );
            lastInputTokens = response.inputTokens();
            lastOutputTokens = response.outputTokens();
            if (response.hasToolCalls()) {
                String toolSkillPrompt = ToolSkillResolver.defaults().renderToolCallUsage(response.toolCalls());
                if (injectToolSkillPrompt(toolSkillPrompt)) {
                    continue;
                }
                stepObserver.onEvent("tool-iteration#" + iteration + " calls=" + summarizeToolCalls(response.toolCalls()));
                history.add(LlmClient.Message.assistant(response.content(), response.toolCalls()));
                for (ToolRegistry.ToolExecutionResult result : toolRegistry.executeTools(response.toolCalls())) {
                    history.add(LlmClient.Message.tool(result.toolCallId(), result.result()));
                }
                continue;
            }
            stepObserver.onEvent("decision-raw=" + shorten(response.content()));
            history.add(LlmClient.Message.assistant(response.content(), null));
            return AgentDecision.parse(response.content());
        }

        return AgentDecision.fallback("达到工具调用轮数上限(" + maxToolIterations() + ")，已暂停并等待下一轮。 ");
    }

    public int getContextTokens() {
        return lastInputTokens;
    }

    public int getLastOutputTokens() {
        return lastOutputTokens;
    }

    private int maxToolIterations() {
        return switch (role) {
            case Researcher, Coder -> EXPLORATION_TOOL_ITERATIONS;
            default -> DEFAULT_TOOL_ITERATIONS;
        };
    }

    private List<LlmClient.Tool> toolsForRole() {
        return switch (role) {
            case Researcher -> toolRegistry.getToolDefinitions(List.of(
                    "read_file", "list_dir", "project_tree", "search_files"
            ));
            case Coder -> toolRegistry.getToolDefinitions(List.of(
                    "read_file", "list_dir", "project_tree", "search_files",
                    "write_file", "execute_command"
            ));
            case Reviewer, Tester -> toolRegistry.getToolDefinitions(List.of(
                    "read_file", "list_dir", "project_tree", "search_files"
            ));
            default -> null;
        };
    }

    private String summarizeToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return "[]";
        return toolCalls.stream()
                .map(call -> call == null || call.function() == null ? "unknown" : call.function().name())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String shorten(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 240 ? oneLine.substring(0, 237) + "..." : oneLine;
    }

    public interface StepObserver {
        StepObserver NO_OP = event -> {};

        void onEvent(String event);
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
        return SkillPrompts.addendum("core/team-agent", """
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
                   - spawn_subagent: {"type":"spawn_subagent","role":"Researcher|Coder","mode":"read|write","title":"子任务标题","prompt":"子任务说明","parent_task_id":"task_1","allowed_tools":["project_tree","search_files","read_file"]}
                   - spawn_subagents: {"type":"spawn_subagents","parent_task_id":"task_1","children":[{"title":"子任务A","role":"Researcher|Coder","mode":"read|write","prompt":"...","allowed_tools":["project_tree","search_files","read_file"]}]}
                   - final_answer: {"type":"final_answer","content":"最终答复"}
                3. 不要重复认领已有 owner 的任务。依赖未完成时，不要开始任务。
                4. 不要向正在 IN_PROGRESS/CLAIMED 的任务 owner 反复询问“进展如何”；运行时会显示心跳。若确实阻塞，请改为 cancel_task、重新委派或等待审查结果。
                5. 你可以通过消息请求其他 Agent 协助、质疑方案或要求审查。
                6. 若你是 Coordinator，优先创建少量必要任务、取消重复/过期任务，并尽早判断是否可以最终收尾。Coordinator 不要使用 spawn_subagent。
                7. 当前运行目录就是用户项目根目录；分析“当前项目”时，直接创建探索任务并使用 list_dir/read_file，不要反复向 user 索要路径或授权。
                8. 若你是 Coder/Researcher，看到适合自己的 TODO/REJECTED 任务时可以主动 claim/start，并在必要时调用工具。
                9. 若你是 Coder/Researcher，且当前任务可以拆成多个互不依赖的只读探索问题，优先使用一个 spawn_subagents 批量 action 创建 2-3 个 mode=read 子Agent。子Agent结果会以结构化 ANSWER 回到你的 inbox；你需要自己聚合，不要让子Agent直接收尾。
                10. mode=read 子Agent只能用于只读探索、代码阅读、方案比较、验证或 patch plan 建议。不要要求 read 子Agent写文件、创建任务、最终答复、审批任务或修改 Git 状态。
                11. 若你是 Researcher，遇到“项目结构/组织架构/优化方向/关键文件/技术栈”这类项目级探索任务，优先输出 claim_task、start_task 和 spawn_subagents；不要先自己连续调用工具。等待子Agent ANSWER 后再汇总。
                12. Researcher 只能使用只读工具，不要输出 write_file/create_project/execute_command 写入或修改类 action；如需修改，向 coordinator 或 coder 提交建议。
                13. 若你是 Coder，只有任务明确要求实现、修改代码、创建项目或验证代码时才认领；不要认领明显偏探索/调研的任务。
                14. 只有 Coder 可以创建 mode=write 子Agent。Coder 面对新项目、多文件修改、核心逻辑+测试、或影响面不清晰的任务时，优先 claim/start 后使用 spawn_subagents：可用 mode=read 子Agent先拆解方案，也可把边界清晰、互不重叠的局部写入交给 1-3 个 mode=write Coder 子Agent。write 子Agent可以写文件，但父 Coder 仍负责聚合、验证和提交审查。
                15. Coder 写文件时要分批推进：先创建必要骨架或核心文件，拿到工具结果后再继续，不要在同一次 LLM 工具循环中连续写大量文件和执行大量命令导致工具轮数耗尽。若使用 write 子Agent，请给每个子Agent明确文件范围，避免多个子Agent写同一文件。
                16. Coder 写文件后必须在 artifact 中列出修改文件、修改目的、验证方式和验证结果；能运行测试时优先运行测试或说明未运行原因。
                17. 若你是 Tester，默认只做独立审查和一致性检查，不主动扫描全项目，除非 coordinator 明确要求。
                18. 若你是 Reviewer，看到 READY_FOR_REVIEW 任务时主动审查并 approve/reject；APPROVED 即视为可交付，不必要求 owner 再 complete_task。Reviewer 可以使用只读工具审查产物，但不能写文件。
                19. Reviewer 工具预算很小，最多约 4 轮工具调用。每次工具返回后都要根据剩余轮数重新规划：优先阅读提交 artifact、项目树和最关键的入口/核心文件；如果剩余轮数不足，不要继续扩大读取范围，而是基于已有证据给出 approve/reject 或带风险说明的 partial review。
                20. Reviewer 不要在同一轮里批量读取大量文件。读取文件前先用 artifact/project_tree/list_dir 判断最小审查集合；通常只读取能改变审查结论的关键文件，例如 pom/build 配置、入口类、核心接口/状态机、被声明修改的文件。若任务是新项目或多文件交付，抽样审查关键链路即可，不要穷尽全项目。
                """);
    }

    private boolean injectToolSkillPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        int key = prompt.hashCode();
        if (!injectedToolSkillPrompts.add(key)) {
            return false;
        }
        history.add(LlmClient.Message.user(prompt));
        return true;
    }
}
