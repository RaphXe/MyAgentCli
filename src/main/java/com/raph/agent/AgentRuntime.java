package com.raph.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raph.llm.LlmClient;
import com.raph.tool.ToolRegistry;
import com.raph.render.PlainRenderer;
import com.raph.render.Renderer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 自治 Multi-Agent Runtime：负责投递消息、维护 TaskBoard、限制轮数和执行 Agent actions。
 */
public class AgentRuntime {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_ROUNDS = 8;
    private static final int RECENT_TRANSCRIPT_LIMIT = 20;
    private static final int HEARTBEAT_SECONDS = 5;
    private static final int MAX_AGENT_STEPS = 32;

    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final MessageBus messageBus = new MessageBus();
    private final TaskBoard taskBoard = new TaskBoard();
    private final Map<String, TeamAgent> agents = new LinkedHashMap<>();
    private final int maxRounds;
    private final Renderer renderer;
    private String finalAnswer;

    public AgentRuntime(LlmClient client, ToolRegistry toolRegistry) {
        this(client, toolRegistry, DEFAULT_MAX_ROUNDS, new PlainRenderer(System.out));
    }

    public AgentRuntime(LlmClient client, ToolRegistry toolRegistry, PrintStream out) {
        this(client, toolRegistry, DEFAULT_MAX_ROUNDS, new PlainRenderer(out));
    }

    public AgentRuntime(LlmClient client, ToolRegistry toolRegistry, Renderer renderer) {
        this(client, toolRegistry, DEFAULT_MAX_ROUNDS, renderer);
    }

    public AgentRuntime(LlmClient client, ToolRegistry toolRegistry, int maxRounds) {
        this(client, toolRegistry, maxRounds, new PlainRenderer(System.out));
    }

    public AgentRuntime(LlmClient client, ToolRegistry toolRegistry, int maxRounds, PrintStream out) {
        this(client, toolRegistry, maxRounds, new PlainRenderer(out));
    }

    public AgentRuntime(LlmClient client, ToolRegistry toolRegistry, int maxRounds, Renderer renderer) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.maxRounds = maxRounds <= 0 ? DEFAULT_MAX_ROUNDS : maxRounds;
        this.renderer = renderer == null ? new PlainRenderer(System.out) : renderer;
        registerDefaultTeam();
    }

    public record RuntimeView(String goal, String taskBoard, String recentTranscript) {}

    public String run(String goal) throws IOException {
        finalAnswer = null;
        String threadId = "team-" + System.currentTimeMillis();
        messageBus.send(AgentMessage.of(threadId, "user", "coordinator", AgentMessage.Type.GOAL, goal));

        StringBuilder log = new StringBuilder();
        logLine(log, "👥 自治 Multi-Agent 团队启动");
        logLine(log, "目标：" + goal);
        logLine(log, "团队：" + String.join(", ", agents.keySet()));
        logLine(log, "上限：round=" + maxRounds + ", agentSteps=" + MAX_AGENT_STEPS + "\n");

        int idleRounds = 0;
        int agentSteps = 0;
        for (int round = 1; round <= maxRounds && agentSteps < MAX_AGENT_STEPS; round++) {
            boolean changed = false;
            logLine(log, "┌─ Round " + round + " ─────────────────────────");
            logLine(log, "│ 任务板快照：\n" + indent(taskBoard.renderSummary(), "│   "));

            for (TeamAgent agent : agents.values()) {
                if (agentSteps >= MAX_AGENT_STEPS) {
                    logLine(log, "│ ⚠ 达到 Agent step 上限，停止继续唤醒。 ");
                    break;
                }

                List<AgentMessage> rawInbox = messageBus.drain(agent.id());
                List<AgentMessage> inbox = filterInbox(agent, rawInbox);
                int dropped = rawInbox.size() - inbox.size();
                if (!shouldWake(agent, inbox, round)) {
                    logLine(log, "│ ⏭ skip " + agent.id() + " (" + agent.role() + ") inbox=" + inbox.size()
                            + (dropped > 0 ? ", dropped=" + dropped : ""));
                    continue;
                }

                agentSteps++;
                logLine(log, "│ ▶ step#" + agentSteps + " " + agent.id() + " (" + agent.role() + ") inbox=" + inbox.size());
                AgentDecision decision = callAgentWithHeartbeat(agent, view(goal), inbox, log);
                changed |= applyDecision(threadId, agent, decision, log);

                if (finalAnswer != null) {
                    logLine(log, "│ ✅ 已有最终答案，提前收束。 ");
                    break;
                }
            }

            logLine(log, "└─ Round " + round + " 结束，changed=" + changed + "\n");

            if (finalAnswer != null) {
                break;
            }

            if (!changed) {
                idleRounds++;
                if (idleRounds >= 2) {
                    logLine(log, "⚠ 连续两轮没有状态变化，唤醒 coordinator 做收束/重规划。 ");
                    messageBus.send(AgentMessage.of(threadId, "runtime", "coordinator",
                            AgentMessage.Type.BLOCKED, "连续两轮没有有效状态变化，请判断是否需要创建任务、重新委派或给出最终结论。"));
                    idleRounds = 0;
                }
            } else {
                idleRounds = 0;
            }
        }

        if (finalAnswer == null) {
            finalAnswer = buildFallbackSummary();
        }
        logLine(log, "✅ 团队输出：\n" + finalAnswer);
        return "✅ Multi-Agent 自治调度结束。";
    }

    private AgentDecision callAgentWithHeartbeat(TeamAgent agent, RuntimeView view,
                                                 List<AgentMessage> inbox, StringBuilder log) throws IOException {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "team-agent-" + agent.id());
            t.setDaemon(true);
            return t;
        });
        long start = System.currentTimeMillis();
        Future<AgentDecision> future = executor.submit(() -> agent.step(view, inbox, LlmClient.StreamListener.NO_OP));
        try {
            while (true) {
                try {
                    AgentDecision decision = future.get(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                    long elapsed = (System.currentTimeMillis() - start) / 1000;
                    int actionCount = decision == null || decision.actions() == null ? 0 : decision.actions().size();
                    logLine(log, "│   ✓ " + agent.id() + " 返回，用时 " + elapsed + "s，actions=" + actionCount
                            + ", status=" + (decision == null ? "null" : decision.status()));
                    return decision;
                } catch (TimeoutException ignored) {
                    long elapsed = (System.currentTimeMillis() - start) / 1000;
                    logLine(log, "│   … " + agent.id() + " LLM/工具调用中，已等待 " + elapsed + "s");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Agent 调度被中断: " + agent.id(), e);
        } catch (Exception e) {
            throw new IOException("Agent 执行失败: " + agent.id() + " - " + e.getMessage(), e);
        } finally {
            executor.shutdownNow();
        }
    }

    private void registerDefaultTeam() {
        addAgent(new TeamAgent("coordinator", AgentRole.Coordinator, client, toolRegistry));
        addAgent(new TeamAgent("researcher", AgentRole.Researcher, client, toolRegistry));
        addAgent(new TeamAgent("coder", AgentRole.Coder, client, toolRegistry));
        addAgent(new TeamAgent("reviewer", AgentRole.Reviewer, client, toolRegistry));
        addAgent(new TeamAgent("tester", AgentRole.Tester, client, toolRegistry));
    }

    private void addAgent(TeamAgent agent) {
        agents.put(agent.id(), agent);
        messageBus.registerAgent(agent.id());
    }

    private RuntimeView view(String goal) {
        return new RuntimeView(goal, taskBoard.renderForPrompt(), renderRecentTranscript());
    }

    private List<AgentMessage> filterInbox(TeamAgent agent, List<AgentMessage> inbox) {
        if (inbox == null || inbox.isEmpty()) return List.of();
        if (agent.role() == AgentRole.Coordinator) return inbox;
        List<AgentMessage> filtered = new ArrayList<>();
        for (AgentMessage message : inbox) {
            if (message.type() == AgentMessage.Type.FINAL || message.type() == AgentMessage.Type.REJECT) {
                filtered.add(message);
                continue;
            }
            if (agent.role() == AgentRole.Reviewer) {
                if (message.type() == AgentMessage.Type.REVIEW || !taskBoard.reviewableTasks().isEmpty()) {
                    filtered.add(message);
                }
                continue;
            }
            if (agent.role() == AgentRole.Tester) {
                if (message.type() == AgentMessage.Type.REVIEW
                        || (message.type() == AgentMessage.Type.REQUEST_HELP && "coordinator".equals(message.senderId()))) {
                    filtered.add(message);
                }
                continue;
            }
            if (agent.role() == AgentRole.Coder || agent.role() == AgentRole.Researcher) {
                if (message.type() == AgentMessage.Type.DELEGATE
                        || message.type() == AgentMessage.Type.REQUEST_HELP
                        || message.type() == AgentMessage.Type.ANSWER
                        || taskBoard.hasActiveTaskFor(agent.id())) {
                    filtered.add(message);
                }
            }
        }
        return filtered;
    }

    private boolean shouldWake(TeamAgent agent, List<AgentMessage> inbox, int round) {
        if (!inbox.isEmpty()) return true;
        if (round == 1 && agent.role() == AgentRole.Coordinator) return true;
        if (agent.role() == AgentRole.Reviewer && !taskBoard.reviewableTasks().isEmpty()) return true;
        if (taskBoard.hasActiveTaskFor(agent.id())) return true;
        if ((agent.role() == AgentRole.Coder || agent.role() == AgentRole.Researcher)
                && hasUnownedReadyTask()) return true;
        return agent.role() == AgentRole.Coordinator && taskBoard.allTerminal() && finalAnswer == null;
    }

    private boolean applyDecision(String threadId, TeamAgent agent, AgentDecision decision, StringBuilder log) {
        boolean changed = false;
        if (decision == null) return false;

        if (decision.finalAnswer() != null && !decision.finalAnswer().isBlank()) {
            finalAnswer = decision.finalAnswer();
            messageBus.send(AgentMessage.of(threadId, agent.id(), AgentMessage.BROADCAST,
                    AgentMessage.Type.FINAL, finalAnswer));
            logLine(log, "│   ★ final: " + shorten(finalAnswer));
            changed = true;
        }

        for (AgentDecision.Action action : decision.actions()) {
            if (action == null || action.type() == null) continue;
            String type = action.type().trim().toLowerCase();
            switch (type) {
                case "send_message" -> {
                    AgentMessage.Type messageType = parseMessageType(action.messageType());
                    String to = normalizeAgentId(blankToDefault(action.to(), AgentMessage.BROADCAST));
                    if (shouldSuppressStatusPing(to, messageType, action.content())) {
                        logLine(log, "│   ⊘ suppress status ping -> " + to + ": " + shorten(action.content()));
                    } else {
                        messageBus.send(AgentMessage.of(threadId, agent.id(), to, messageType, action.content()));
                        logLine(log, "│   ✉ msg -> " + to + " [" + messageType + "]: " + shorten(action.content()));
                        changed = true;
                    }
                }
                case "create_task" -> {
                    TaskBoard.TaskItem task = taskBoard.createTask(action.title(), action.description(), action.dependencies());
                    String delegateTarget = inferDelegateTarget(task);
                    messageBus.send(AgentMessage.withMetadata(threadId, agent.id(), delegateTarget,
                            AgentMessage.Type.DELEGATE,
                            "创建任务 " + task.id() + ": " + task.title(),
                            Map.of("task_id", task.id())));
                    logLine(log, "│   ＋ create " + task.id() + ": " + task.title() + " -> " + delegateTarget);
                    changed = true;
                }
                case "claim_task" -> {
                    boolean ok = taskBoard.claimTask(action.taskId(), agent.id());
                    logLine(log, "│   ◇ claim " + action.taskId() + (ok ? " ok" : " ignored"));
                    changed |= ok;
                }
                case "start_task" -> {
                    boolean ok = taskBoard.startTask(action.taskId(), agent.id());
                    logLine(log, "│   ▶ start " + action.taskId() + (ok ? " ok" : " ignored"));
                    changed |= ok;
                }
                case "ready_for_review" -> {
                    boolean ok = taskBoard.markReadyForReview(action.taskId(), agent.id(), action.artifact());
                    if (ok) {
                        messageBus.send(AgentMessage.withMetadata(threadId, agent.id(), "reviewer",
                                AgentMessage.Type.REVIEW,
                                "请审查 " + action.taskId() + ": " + blankToDefault(action.artifact(), "已提交产物"),
                                Map.of("task_id", action.taskId())));
                    }
                    logLine(log, "│   ◆ review-ready " + action.taskId() + (ok ? " ok" : " ignored"));
                    changed |= ok;
                }
                case "approve_task" -> {
                    boolean ok = taskBoard.approveTask(action.taskId(), agent.id(), action.note());
                    if (ok) {
                        messageBus.send(AgentMessage.withMetadata(threadId, agent.id(), "coordinator",
                                AgentMessage.Type.APPROVE,
                                "任务 " + action.taskId() + " 已通过审查，视为可收束状态。" + blankToDefault(action.note(), ""),
                                Map.of("task_id", action.taskId())));
                    }
                    logLine(log, "│   ✓ approve " + action.taskId() + (ok ? " ok" : " ignored"));
                    changed |= ok;
                }
                case "reject_task" -> {
                    boolean ok = taskBoard.rejectTask(action.taskId(), agent.id(), action.reason());
                    if (ok) {
                        TaskBoard.TaskItem task = taskBoard.get(action.taskId());
                        if (task != null && task.ownerAgentId() != null) {
                            messageBus.send(AgentMessage.withMetadata(threadId, agent.id(), task.ownerAgentId(),
                                    AgentMessage.Type.REJECT,
                                    "任务 " + action.taskId() + " 被打回：" + blankToDefault(action.reason(), "请修正后重新提交。"),
                                    Map.of("task_id", action.taskId())));
                        }
                    }
                    logLine(log, "│   ✗ reject " + action.taskId() + (ok ? " ok" : " ignored"));
                    changed |= ok;
                }
                case "complete_task" -> {
                    TaskBoard.TaskItem task = taskBoard.get(action.taskId());
                    if (task != null && task.status() == TaskBoard.TaskStatus.APPROVED) {
                        logLine(log, "│   ⊘ complete " + action.taskId() + " skipped (APPROVED already terminal)");
                    } else {
                        boolean ok = taskBoard.completeTask(action.taskId(), agent.id());
                        logLine(log, "│   ✓ complete " + action.taskId() + (ok ? " ok" : " ignored"));
                        changed |= ok;
                    }
                }
                case "cancel_task" -> {
                    boolean ok = taskBoard.cancelTask(action.taskId(), agent.id(), action.reason());
                    logLine(log, "│   × cancel " + action.taskId() + (ok ? " ok" : " ignored"));
                    changed |= ok;
                }
                case "block_task" -> {
                    boolean ok = taskBoard.blockTask(action.taskId(), agent.id(), action.reason());
                    if (ok) {
                        messageBus.send(AgentMessage.withMetadata(threadId, agent.id(), "coordinator",
                                AgentMessage.Type.BLOCKED,
                                "任务 " + action.taskId() + " 阻塞：" + blankToDefault(action.reason(), "未说明原因"),
                                Map.of("task_id", action.taskId())));
                    }
                    logLine(log, "│   ! block " + action.taskId() + (ok ? " ok" : " ignored"));
                    changed |= ok;
                }
                case "final_answer" -> {
                    finalAnswer = action.content();
                    messageBus.send(AgentMessage.of(threadId, agent.id(), AgentMessage.BROADCAST,
                            AgentMessage.Type.FINAL, finalAnswer));
                    logLine(log, "│   ★ final: " + shorten(finalAnswer));
                    changed = true;
                }
                case "read_file", "write_file", "list_dir", "execute_command", "create_project" -> {
                    String result = executeToolAction(type, action);
                    messageBus.send(AgentMessage.of(threadId, "tool:" + type, agent.id(),
                            AgentMessage.Type.ANSWER, result));
                    logLine(log, "│   🔧 tool " + type + " -> " + agent.id() + ": " + shorten(result));
                    changed = true;
                }
                default -> logLine(log, "│   ? unknown action: " + action.type());
            }
        }
        return changed;
    }

    private String inferDelegateTarget(TaskBoard.TaskItem task) {
        String text = ((task.title() == null ? "" : task.title()) + " "
                + (task.description() == null ? "" : task.description())).toLowerCase();
        if (text.contains("审查") || text.contains("review")) return "reviewer";
        if (text.contains("验证") || text.contains("测试") || text.contains("test")) return "tester";
        if (text.contains("探索") || text.contains("结构") || text.contains("技术栈")
                || text.contains("读取") || text.contains("收集")) return "researcher";
        if (text.contains("优化") || text.contains("分析") || text.contains("实现") || text.contains("代码")) return "coder";
        return "coordinator";
    }

    private boolean hasUnownedReadyTask() {
        return taskBoard.readyTasks().stream().anyMatch(task -> task.ownerAgentId() == null);
    }

    private String normalizeAgentId(String id) {
        if (id == null || id.isBlank() || AgentMessage.BROADCAST.equals(id)) return AgentMessage.BROADCAST;
        String lower = id.trim().toLowerCase();
        return agents.containsKey(lower) ? lower : id.trim();
    }

    private boolean shouldSuppressStatusPing(String to, AgentMessage.Type messageType, String content) {
        if (to == null || AgentMessage.BROADCAST.equals(to) || "coordinator".equals(to)) return false;
        if (messageType != AgentMessage.Type.REQUEST_HELP && messageType != AgentMessage.Type.REPORT_PROGRESS) return false;
        if (!taskBoard.hasActiveTaskOwnedBy(to)) return false;
        String lower = content == null ? "" : content.toLowerCase();
        return lower.contains("进展")
                || lower.contains("状态")
                || lower.contains("如何")
                || lower.contains("是否")
                || lower.contains("progress")
                || lower.contains("status");
    }

    private void requestFinalAnswer(String threadId, String goal, StringBuilder log) throws IOException {
        TeamAgent coordinator = agents.get("coordinator");
        if (coordinator == null) return;
        logLine(log, "⚑ 达到调度边界，强制请求 coordinator 基于现有 transcript 输出最终报告。 ");
        List<AgentMessage> inbox = List.of(AgentMessage.of(threadId, "runtime", "coordinator",
                AgentMessage.Type.FINAL,
                "请立即基于当前任务板和团队 transcript 输出 final_answer。即使任务未全部 DONE，也要总结已有发现、未完成项和下一步建议。"));
        AgentDecision decision = callAgentWithHeartbeat(coordinator, view(goal), inbox, log);
        applyDecision(threadId, coordinator, decision, log);
    }

    private String executeToolAction(String toolName, AgentDecision.Action action) {
        ObjectNode args = MAPPER.createObjectNode();
        if (action.params() != null) {
            action.params().forEach((key, value) -> {
                if (value == null) args.putNull(key);
                else args.put(key, value);
            });
        }
        // 常见模型会把参数写在 content/description 里，给几个保守兜底。
        if (!args.has("path") && ("read_file".equals(toolName) || "list_dir".equals(toolName))) {
            String path = firstNonBlank(action.content(), action.description(), ".");
            args.put("path", path);
        }
        if (!args.has("command") && "execute_command".equals(toolName)) {
            args.put("command", firstNonBlank(action.content(), action.description(), "pwd"));
        }
        return toolRegistry.executeTool(toolName, args.toString());
    }

    private String renderRecentTranscript() {
        List<AgentMessage> messages = messageBus.recentTranscript(RECENT_TRANSCRIPT_LIMIT);
        if (messages.isEmpty()) return "暂无。";
        List<String> lines = new ArrayList<>();
        for (AgentMessage message : messages) {
            lines.add(message.senderId() + " -> " + message.receiverId() + " [" + message.type() + "] "
                    + shorten(message.content()));
        }
        return String.join("\n", lines);
    }

    private void logLine(StringBuilder log, String line) {
        log.append(line).append("\n");
        renderer.println(line);
    }

    private static String indent(String value, String prefix) {
        if (value == null || value.isBlank()) return prefix + "(empty)";
        return prefix + value.replace("\n", "\n" + prefix);
    }

    private String buildFallbackSummary() {
        return "自治团队已达到运行轮数上限。当前任务板：\n" + taskBoard.renderSummary();
    }

    private static AgentMessage.Type parseMessageType(String raw) {
        if (raw == null || raw.isBlank()) return AgentMessage.Type.REPORT_PROGRESS;
        try {
            return AgentMessage.Type.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AgentMessage.Type.REPORT_PROGRESS;
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String shorten(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 177) + "..." : oneLine;
    }
}
