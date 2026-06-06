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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
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
    private static final int MAX_PARALLEL_SUBAGENTS = 3;
    private static final int SUBAGENT_TIMEOUT_SECONDS = 180;

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
                            + (dropped > 0 ? ", dropped=" + dropped + " " + summarizeMessages(rawInbox, inbox) : ""));
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
        Future<AgentDecision> future = executor.submit(() -> agent.step(
                view,
                inbox,
                LlmClient.StreamListener.NO_OP,
                event -> logLine(log, "│   · " + agent.id() + " " + event)
        ));
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
                if (message.type() == AgentMessage.Type.DELEGATE
                        || message.type() == AgentMessage.Type.REVIEW
                        || !taskBoard.reviewableTasks().isEmpty()) {
                    filtered.add(message);
                }
                continue;
            }
            if (agent.role() == AgentRole.Tester) {
                if (message.type() == AgentMessage.Type.DELEGATE
                        || message.type() == AgentMessage.Type.REVIEW
                        || (message.type() == AgentMessage.Type.REQUEST_HELP && "coordinator".equals(message.senderId()))) {
                    filtered.add(message);
                }
                continue;
            }
            if (agent.role() == AgentRole.Coder || agent.role() == AgentRole.Researcher) {
                if (message.type() == AgentMessage.Type.DELEGATE
                        || message.type() == AgentMessage.Type.REQUEST_HELP
                        || (message.type() == AgentMessage.Type.REPORT_PROGRESS
                        && "coordinator".equals(message.senderId()))
                        || message.type() == AgentMessage.Type.ANSWER
                        || taskBoard.hasActiveTaskFor(agent.id())) {
                    filtered.add(message);
                }
            }
        }
        return filtered;
    }

    private String summarizeMessages(List<AgentMessage> rawInbox, List<AgentMessage> keptInbox) {
        if (rawInbox == null || rawInbox.isEmpty()) {
            return "";
        }
        java.util.Set<String> keptIds = keptInbox == null ? java.util.Set.of()
                : keptInbox.stream().map(AgentMessage::id).collect(java.util.stream.Collectors.toSet());
        List<String> dropped = new ArrayList<>();
        for (AgentMessage message : rawInbox) {
            if (!keptIds.contains(message.id())) {
                dropped.add(message.senderId() + "/" + message.type());
            }
        }
        return dropped.isEmpty() ? "" : "droppedTypes=" + dropped;
    }

    private boolean shouldWake(TeamAgent agent, List<AgentMessage> inbox, int round) {
        if (!inbox.isEmpty()) return true;
        if (round == 1 && agent.role() == AgentRole.Coordinator) return true;
        if (agent.role() == AgentRole.Reviewer && !taskBoard.reviewableTasks().isEmpty()) return true;
        if (taskBoard.hasActiveTaskFor(agent.id())) return true;
        if ((agent.role() == AgentRole.Coder || agent.role() == AgentRole.Researcher)
                && hasUnownedReadyTaskFor(agent)) return true;
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

        List<AgentDecision.Action> actions = decision.actions() == null ? List.of() : decision.actions();
        List<SubAgentRequestSpec> subAgentRequests = collectSubAgentRequests(actions);
        if (!subAgentRequests.isEmpty()) {
            if (agent.role() == AgentRole.Researcher || agent.role() == AgentRole.Coder) {
                changed |= runSubAgents(threadId, agent, subAgentRequests, log);
            } else {
                logLine(log, "│   ⊘ spawn_subagent ignored for " + agent.id() + " (" + agent.role()
                        + "); only Researcher/Coder can spawn subagents");
            }
        }

        for (AgentDecision.Action action : actions) {
            if (action == null || action.type() == null) continue;
            String type = action.type().trim().toLowerCase();
            switch (type) {
                case "spawn_subagent", "spawn_subagents" -> {
                    // 子 Agent 已按批次并发执行，结果通过 ANSWER 回投给父 Agent。
                }
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
                    String reason = canClaimTask(agent, action.taskId());
                    boolean ok = reason == null && taskBoard.claimTask(action.taskId(), agent.id());
                    logLine(log, "│   ◇ claim " + action.taskId() + (ok ? " ok" : " ignored"
                            + (reason == null ? claimFailureReason(action.taskId(), agent.id()) : " (" + reason + ")")));
                    changed |= ok;
                }
                case "start_task" -> {
                    boolean ok = taskBoard.startTask(action.taskId(), agent.id());
                    logLine(log, "│   ▶ start " + action.taskId() + (ok ? " ok" : " ignored ("
                            + startFailureReason(action.taskId(), agent.id()) + ")"));
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
                case "read_file", "write_file", "list_dir", "project_tree", "search_files",
                        "execute_command", "create_project" -> {
                    if (!canExecuteToolAction(agent, type)) {
                        logLine(log, "│   ⊘ tool " + type + " denied for " + agent.id() + " (" + agent.role() + ")");
                    } else {
                        String result = executeToolAction(type, action);
                        messageBus.send(AgentMessage.of(threadId, "tool:" + type, agent.id(),
                                AgentMessage.Type.ANSWER, result));
                        logLine(log, "│   🔧 tool " + type + " -> " + agent.id() + ": " + shorten(result));
                        changed = true;
                    }
                }
                default -> logLine(log, "│   ? unknown action: " + action.type());
            }
        }
        return changed;
    }

    private List<SubAgentRequestSpec> collectSubAgentRequests(List<AgentDecision.Action> actions) {
        List<SubAgentRequestSpec> requests = new ArrayList<>();
        if (actions == null) return requests;
        for (AgentDecision.Action action : actions) {
            if (action == null || action.type() == null) continue;
            String type = action.type().trim().toLowerCase();
            if ("spawn_subagent".equals(type)) {
                requests.add(new SubAgentRequestSpec(
                        action.title(), action.role(), action.prompt(), action.content(),
                        action.parentTaskId(), action.allowedTools(), action.mode()
                ));
            } else if ("spawn_subagents".equals(type)) {
                if (action.children() == null || action.children().isEmpty()) {
                    requests.add(new SubAgentRequestSpec(
                            action.title(), action.role(), action.prompt(), action.content(),
                            action.parentTaskId(), action.allowedTools(), action.mode()
                    ));
                    continue;
                }
                for (AgentDecision.SubAgentChild child : action.children()) {
                    requests.add(new SubAgentRequestSpec(
                            child.title(), child.role(), child.prompt(), action.content(),
                            action.parentTaskId(),
                            child.allowedTools() == null || child.allowedTools().isEmpty()
                                    ? action.allowedTools() : child.allowedTools(),
                            child.mode() == null || child.mode().isBlank() ? action.mode() : child.mode()
                    ));
                }
            }
        }
        return requests.size() > MAX_PARALLEL_SUBAGENTS ? requests.subList(0, MAX_PARALLEL_SUBAGENTS) : requests;
    }

    private boolean runSubAgents(String threadId, TeamAgent parentAgent,
                                 List<SubAgentRequestSpec> requests, StringBuilder log) {
        int poolSize = Math.min(MAX_PARALLEL_SUBAGENTS, requests.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "sub-agent-" + parentAgent.id());
            t.setDaemon(true);
            return t;
        });
        CompletionService<SubAgentRunResult> completionService = new ExecutorCompletionService<>(executor);
        List<Future<SubAgentRunResult>> futures = new ArrayList<>();

        logLine(log, "│   ⇢ spawn subagent batch parent=" + parentAgent.id() + " children=" + requests.size());
        for (SubAgentRequestSpec rawRequest : requests) {
            SubAgentRequestSpec request = normalizeSubAgentRequest(parentAgent, rawRequest, log);
            if (request == null) {
                continue;
            }
            logLine(log, "│     - child " + blankToDefault(request.title(), "untitled")
                    + " role=" + blankToDefault(request.role(), "Researcher")
                    + " mode=" + request.mode());
            futures.add(completionService.submit(() -> runOneSubAgent(request, log)));
        }

        if (futures.isEmpty()) {
            return false;
        }

        boolean changed = false;
        List<SubAgentRunResult> results = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(SUBAGENT_TIMEOUT_SECONDS);
        int completed = 0;
        try {
            while (completed < futures.size()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                Future<SubAgentRunResult> future = completionService.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (future == null) {
                    break;
                }
                completed++;
                SubAgentRunResult result;
                try {
                    result = future.get();
                } catch (Exception e) {
                    result = new SubAgentRunResult("子Agent执行失败", "Researcher", null,
                            SubAgentRunner.Report.incomplete("子Agent执行失败: " + e.getMessage(), null),
                            true, new SubAgentRunner.RunStats());
                }
                results.add(result);
                logLine(log, "│   ↩ subagent " + blankToDefault(result.title(), "untitled")
                        + " -> " + parentAgent.id()
                        + ": status=" + result.report().status()
                        + ", tools=" + result.stats().toolIterations()
                        + ", files=" + result.report().filesRead().size()
                        + ", writes=" + result.report().filesWritten().size()
                        + ", proposed_changes=" + result.report().proposedChanges().size()
                        + ", summary=" + shorten(result.report().summary()));
                changed = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logLine(log, "│   ! 子Agent批次被中断: " + e.getMessage());
        } finally {
            for (Future<SubAgentRunResult> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            executor.shutdownNow();
        }

        int timedOut = futures.size() - completed;
        if (timedOut > 0) {
            logLine(log, "│   ! subagent timeout/cancelled count=" + timedOut);
            results.add(new SubAgentRunResult("超时子Agent", "Researcher", null,
                    SubAgentRunner.Report.incomplete("有 " + timedOut + " 个子Agent超时或被取消。", null),
                    true, new SubAgentRunner.RunStats()));
            changed = true;
        }
        if (!results.isEmpty()) {
            messageBus.send(AgentMessage.withMetadata(threadId, "subagent:" + parentAgent.id(), parentAgent.id(),
                    AgentMessage.Type.ANSWER, formatSubAgentBatchResult(results), subAgentBatchMetadata(results)));
            logLine(log, "│   ↩ subagent batch -> " + parentAgent.id()
                    + ": 子Agent批次结果 children=" + results.size());
        }
        return changed;
    }

    private SubAgentRequestSpec normalizeSubAgentRequest(TeamAgent parentAgent, SubAgentRequestSpec request, StringBuilder log) {
        String mode = normalizeSubAgentMode(request.mode());
        if ("write".equals(mode) && parentAgent.role() != AgentRole.Coder) {
            logLine(log, "│     ⊘ child " + blankToDefault(request.title(), "untitled")
                    + " write mode denied for parent=" + parentAgent.id() + " (" + parentAgent.role() + ")");
            return null;
        }
        String role = request.role();
        if ("write".equals(mode) && (role == null || role.isBlank())) {
            role = "Coder";
        }
        return new SubAgentRequestSpec(
                request.title(),
                role,
                request.prompt(),
                request.content(),
                request.parentTaskId(),
                request.allowedTools(),
                mode
        );
    }

    private String normalizeSubAgentMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "read";
        }
        String normalized = mode.trim().toLowerCase();
        if ("write".equals(normalized) || "writer".equals(normalized) || "mutation".equals(normalized)) {
            return "write";
        }
        return "read";
    }

    private SubAgentRunResult runOneSubAgent(SubAgentRequestSpec request, StringBuilder log) throws IOException {
        SubAgentRunner runner = new SubAgentRunner(client, toolRegistry);
        SubAgentRunner.Result result = runner.run(new SubAgentRunner.Request(
                request.title(),
                request.role(),
                request.mode(),
                request.prompt(),
                request.content(),
                request.parentTaskId(),
                request.allowedTools()
        ), event -> logLine(log, "│       · subagent " + blankToDefault(request.title(), "untitled")
                + " " + shorten(event)));
        return new SubAgentRunResult(result.title(), result.role(), result.parentTaskId(),
                result.report(), result.incomplete(), result.stats());
    }

    private Map<String, String> subAgentBatchMetadata(List<SubAgentRunResult> results) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("subagent_count", String.valueOf(results.size()));
        long incomplete = results.stream().filter(SubAgentRunResult::incomplete).count();
        metadata.put("incomplete_count", String.valueOf(incomplete));
        for (SubAgentRunResult result : results) {
            if (result.parentTaskId() != null && !result.parentTaskId().isBlank()) {
                metadata.put("parent_task_id", result.parentTaskId());
                break;
            }
        }
        return Map.copyOf(metadata);
    }

    private String formatSubAgentBatchResult(List<SubAgentRunResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("子Agent批次结果\n");
        sb.append("children=").append(results.size()).append("\n");
        int index = 1;
        for (SubAgentRunResult result : results) {
            sb.append("\n## child_").append(index++).append("\n");
            sb.append("title=").append(blankToDefault(result.title(), "未命名子任务")).append("\n");
            sb.append("role=").append(blankToDefault(result.role(), "Researcher")).append("\n");
            if (result.parentTaskId() != null && !result.parentTaskId().isBlank()) {
                sb.append("parent_task_id=").append(result.parentTaskId()).append("\n");
            }
            sb.append("tool_iterations=").append(result.stats().toolIterations()).append("\n");
            if (!result.stats().toolCalls().isEmpty()) {
                sb.append("tool_calls=").append(result.stats().toolCalls()).append("\n");
            }
            sb.append(result.report().compact()).append("\n");
        }
        sb.append("\n请父Agent基于以上结构化结果聚合，不要重复探索已覆盖文件；read 子Agent只提供证据或 proposed_changes，write 子Agent若已写入文件，父 Coder 需要继续负责验证、汇总和提交审查。");
        return sb.toString();
    }

    private String inferDelegateTarget(TaskBoard.TaskItem task) {
        String text = ((task.title() == null ? "" : task.title()) + " "
                + (task.description() == null ? "" : task.description())).toLowerCase();
        if (text.contains("审查") || text.contains("review")) return "reviewer";
        if ((text.contains("探索") || text.contains("查看") || text.contains("读取")
                || text.contains("收集") || text.contains("目录现状") || text.contains("是否存在"))
                && !(text.contains("写入") || text.contains("实现") || text.contains("创建maven")
                || text.contains("创建项目结构") || text.contains("基础文件"))) {
            return "researcher";
        }
        if (text.contains("创建") || text.contains("生成") || text.contains("新增")
                || text.contains("写入") || text.contains("搭建") || text.contains("脚手架")
                || text.contains("基础文件") || text.contains("主入口文件")
                || text.contains("create") || text.contains("write")) {
            return "coder";
        }
        if (text.contains("探索") || text.contains("结构") || text.contains("技术栈")
                || text.contains("读取") || text.contains("收集")) return "researcher";
        if (text.contains("优化方向") || text.contains("优化建议") || text.contains("建议")
                || text.contains("方案") || text.contains("报告")) {
            if (text.contains("实现") || text.contains("修改") || text.contains("编码") || text.contains("代码")) {
                return "coder";
            }
            return "researcher";
        }
        if (text.contains("实现") || text.contains("修改") || text.contains("编码") || text.contains("代码")) return "coder";
        if (text.contains("验证") || text.contains("测试") || text.contains("test")) return "tester";
        if (text.contains("优化") || text.contains("分析")) return "researcher";
        return "coordinator";
    }

    private boolean hasUnownedReadyTask() {
        return taskBoard.readyTasks().stream().anyMatch(task -> task.ownerAgentId() == null);
    }

    private boolean hasUnownedReadyTaskFor(TeamAgent agent) {
        return taskBoard.readyTasks().stream()
                .filter(task -> task.ownerAgentId() == null)
                .anyMatch(task -> canClaimTask(agent, task.id()) == null);
    }

    private String canClaimTask(TeamAgent agent, String taskId) {
        TaskBoard.TaskItem task = taskBoard.get(taskId);
        if (task == null) {
            return "task not found";
        }
        if (task.ownerAgentId() != null && !task.ownerAgentId().isBlank()) {
            return "already owned by " + task.ownerAgentId();
        }
        String target = inferDelegateTarget(task);
        if (isExecutionTarget(target) && !target.equals(agent.id())) {
            return "delegated to " + target;
        }
        return null;
    }

    private boolean isExecutionTarget(String target) {
        return "researcher".equals(target)
                || "coder".equals(target)
                || "reviewer".equals(target)
                || "tester".equals(target);
    }

    private String claimFailureReason(String taskId, String agentId) {
        TaskBoard.TaskItem task = taskBoard.get(taskId);
        if (task == null) {
            return "task not found";
        }
        if (agentId == null || agentId.isBlank()) {
            return "blank agent";
        }
        if (task.status() != TaskBoard.TaskStatus.TODO && task.status() != TaskBoard.TaskStatus.REJECTED) {
            return "status is " + task.status();
        }
        if (task.ownerAgentId() != null && !task.ownerAgentId().isBlank()) {
            return "already owned by " + task.ownerAgentId();
        }
        return "dependencies not done or board rejected claim";
    }

    private String startFailureReason(String taskId, String agentId) {
        TaskBoard.TaskItem task = taskBoard.get(taskId);
        if (task == null) {
            return "task not found";
        }
        if (!agentId.equals(task.ownerAgentId())) {
            return "owner is " + blankToDefault(task.ownerAgentId(), "none");
        }
        if (task.status() != TaskBoard.TaskStatus.CLAIMED && task.status() != TaskBoard.TaskStatus.REJECTED) {
            return "status is " + task.status();
        }
        return "dependencies not done or board rejected start";
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
        if (!args.has("path") && ("read_file".equals(toolName)
                || "list_dir".equals(toolName)
                || "project_tree".equals(toolName)
                || "search_files".equals(toolName))) {
            String path = firstNonBlank(action.content(), action.description(), ".");
            args.put("path", path);
        }
        if (!args.has("command") && "execute_command".equals(toolName)) {
            args.put("command", firstNonBlank(action.content(), action.description(), "pwd"));
        }
        if (!args.has("content") && "write_file".equals(toolName)) {
            args.put("content", firstNonBlank(action.content(), action.description(), ""));
        }
        return toolRegistry.executeTool(toolName, args.toString());
    }

    private boolean canExecuteToolAction(TeamAgent agent, String toolName) {
        if (agent == null || toolName == null) return false;
        return switch (toolName) {
            case "write_file", "create_project" -> agent.role() == AgentRole.Coder;
            case "execute_command" -> agent.role() == AgentRole.Coder;
            case "read_file", "list_dir", "project_tree", "search_files" ->
                    agent.role() == AgentRole.Researcher
                            || agent.role() == AgentRole.Coder
                            || agent.role() == AgentRole.Reviewer
                            || agent.role() == AgentRole.Tester;
            default -> false;
        };
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

    private synchronized void logLine(StringBuilder log, String line) {
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

    private record SubAgentRunResult(
            String title,
            String role,
            String parentTaskId,
            SubAgentRunner.Report report,
            boolean incomplete,
            SubAgentRunner.RunStats stats
    ) {}

    private record SubAgentRequestSpec(
            String title,
            String role,
            String prompt,
            String content,
            String parentTaskId,
            List<String> allowedTools,
            String mode
    ) {}

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
