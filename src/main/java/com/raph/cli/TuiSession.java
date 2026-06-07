package com.raph.cli;

import com.raph.agent.Agent;
import com.raph.agent.AgentRuntime;
import com.raph.agent.PlanExecuteAgent;
import com.raph.hitl.TerminalHitlHandler;
import com.raph.llm.LlmClient;
import com.raph.memory.ContextUsage;
import com.raph.memory.MemoryManager;
import com.raph.plan.ExecutionPlan;
import com.raph.render.Renderer;
import com.raph.tool.ToolRegistry;
import org.jline.reader.LineReader;

import java.io.IOException;

public class TuiSession {
    private static final String PROMPT_CONFIRM = "❓ 是否执行此计划? [y/n]: ";

    private final LineReader reader;
    private final Renderer renderer;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final TerminalHitlHandler hitlHandler;
    private final MemoryManager memoryManager;
    private final Agent agent;
    private final PlanExecuteAgent planAgent;
    private AgentRuntime teamRuntime;
    private SessionMode mode = SessionMode.NORMAL;
    private boolean exitRequested;

    public TuiSession(LineReader reader,
                      Renderer renderer,
                      LlmClient llmClient,
                      ToolRegistry toolRegistry,
                      TerminalHitlHandler hitlHandler,
                      MemoryManager memoryManager,
                      Agent agent,
                      PlanExecuteAgent planAgent) {
        this.reader = reader;
        this.renderer = renderer;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.hitlHandler = hitlHandler;
        this.memoryManager = memoryManager;
        this.agent = agent;
        this.planAgent = planAgent;
    }

    public void run() {
        renderer.println("💡 提示: 输入 '/plan' 切换计划模式, '/team' 切换团队模式, '/hitl on|off' 切换人工审批, '/save <描述>' 保存记忆, '/clear' 清空历史, '/exit' 退出或返回普通模式\n");

        while (true) {
            renderer.print(TuiStatusLine.contextBar(currentContextUsage()));

            String input = reader.readLine(mode.prompt()).trim();
            if (input.isEmpty()) {
                continue;
            }

            TuiCommand command = TuiCommandParser.parse(input);
            if (command.type() != TuiCommand.Type.USER_INPUT) {
                handleCommand(command);
                if (shouldExit()) {
                    break;
                }
                continue;
            }

            switch (mode) {
                case NORMAL -> handleNormalInput(command.arguments());
                case PLAN -> handlePlanInput(command.arguments());
                case TEAM -> handleTeamInput(command.arguments());
            }
        }
    }

    private void handleCommand(TuiCommand command) {
        switch (command.type()) {
            case PLAN -> switchMode(SessionMode.PLAN, "🧠 当前处于计划模式，输入任务目标后我将制定执行计划");
            case TEAM -> handleTeamCommand(command);
            case HITL -> handleHitlCommand(command.arguments());
            case SAVE -> saveMemory(command.arguments());
            case CLEAR -> clearHistory();
            case EXIT -> exitOrReturnToNormal();
            case UNKNOWN -> renderer.println("❌ 未知命令: " + command.rawInput() + "\n");
            case USER_INPUT -> {
            }
        }
    }

    private boolean shouldExit() {
        return exitRequested;
    }

    private void switchMode(SessionMode targetMode, String enterMessage) {
        if (mode == targetMode) {
            mode = SessionMode.NORMAL;
            renderer.println("🚪 已退出" + targetMode.displayName() + "\n");
        } else {
            mode = targetMode;
            renderer.println(enterMessage);
            renderer.println("   (再次输入 '/" + targetMode.name().toLowerCase() + "' 或输入 '/exit' 返回普通模式)\n");
        }
    }

    private void handleNormalInput(String input) {
        renderer.print("🤔 思考中...");
        String response;
        Renderer.StreamHandle streamRenderer = renderer.contentStream("🤖 Agent: ");
        try {
            response = agent.run(input, streamRenderer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (streamRenderer.hasContent()) {
            renderer.println("");
        } else {
            renderer.print("\r              \r");
            renderer.println("🤖 Agent: " + response);
        }
        renderer.print(TuiStatusLine.tokenSummary(agent));
    }

    private ContextUsage currentContextUsage() {
        return switch (mode) {
            case NORMAL -> TuiStatusLine.normalContextUsage(memoryManager);
            case PLAN -> planAgent.currentContextUsage();
            case TEAM -> teamRuntime == null
                    ? new ContextUsage("团队模式", 0, memoryManager.getTokenBudget().getMaxContextTokens(), "未启动")
                    : teamRuntime.currentContextUsage();
        };
    }

    private void handleHitlCommand(String arguments) {
        String option = arguments == null ? "" : arguments.trim().toLowerCase();
        if (option.isEmpty()) {
            renderer.println("🛡️ HITL 当前状态: " + (hitlHandler.isEnabled() ? "启用" : "关闭") + "\n");
            return;
        }
        if ("on".equals(option)) {
            hitlHandler.setEnabled(true);
            renderer.println("🛡️ HITL 人工审批已启用。危险工具将先请求确认。\n");
            return;
        }
        if ("off".equals(option)) {
            hitlHandler.setEnabled(false);
            hitlHandler.clearApprovedAll();
            renderer.println("🛡️ HITL 人工审批已关闭，全部放行缓存已清空。\n");
            return;
        }
        renderer.println("❌ 用法: /hitl [on|off]\n");
    }

    private void handleTeamCommand(TuiCommand command) {
        if (command.hasArguments()) {
            if (mode != SessionMode.TEAM) {
                mode = SessionMode.TEAM;
            }
            runTeam(command.arguments());
            return;
        }
        switchMode(SessionMode.TEAM, "👥 当前处于团队模式，输入任务后我将使用自治 Multi-Agent 协作");
    }

    private void clearHistory() {
        switch (mode) {
            case NORMAL -> {
                agent.clearHistory();
                renderer.println("🗑️ 普通模式历史已清空\n");
            }
            case TEAM -> {
                teamRuntime = null;
                renderer.println("🗑️ 团队模式会话记忆已清空\n");
            }
            case PLAN -> renderer.println("🗑️ 计划模式暂无持久会话历史\n");
        }
    }

    private void exitOrReturnToNormal() {
        if (mode == SessionMode.NORMAL) {
            exitRequested = true;
            return;
        }
        SessionMode previousMode = mode;
        mode = SessionMode.NORMAL;
        renderer.println("🚪 已退出" + previousMode.displayName() + "\n");
    }

    private void handleTeamInput(String input) {
        runTeam(input);
    }

    private void runTeam(String teamTask) {
        if (teamTask.isEmpty()) {
            renderer.println("❌ 用法: /team <任务内容>\n");
            return;
        }
        renderer.println("👥 启动自治 Multi-Agent 协作...\n");
        try {
            String response = teamRuntime().run(teamTask);
            renderer.println(response);
        } catch (IOException e) {
            renderer.println("❌ 多 Agent 执行失败: " + e.getMessage() + "\n");
        }
    }

    private AgentRuntime teamRuntime() {
        if (teamRuntime == null) {
            teamRuntime = new AgentRuntime(llmClient, toolRegistry, renderer);
        }
        return teamRuntime;
    }

    private void saveMemory(String description) {
        if (description.isEmpty()) {
            renderer.println("❌ 用法: /save <描述内容>\n");
            return;
        }
        try {
            memoryManager.saveToMemory(description, agent);
            renderer.println("");
        } catch (IOException e) {
            renderer.println("❌ 保存记忆失败: " + e.getMessage() + "\n");
        }
    }

    private void handlePlanInput(String input) {
        renderer.print("🤔 规划中...");
        try {
            ExecutionPlan plan = planAgent.createPlan(input);
            renderer.print("\r              \r");
            renderer.println(planAgent.formatPlan(plan));

            String confirm = reader.readLine(PROMPT_CONFIRM).trim();
            if (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes")) {
                runPlanExecutionLoop(plan);
            } else {
                renderer.println("⏭ 已取消执行\n");
            }
        } catch (IOException e) {
            renderer.print("\r              \r");
            renderer.println("❌ 计划创建失败: " + e.getMessage() + "\n");
        }
    }

    private void runPlanExecutionLoop(ExecutionPlan plan) {
        renderer.println("\n🚀 用户确认，开始执行计划...\n");
        PlanExecuteAgent.ExecutionResult result = planAgent.executePlan(plan, renderer);

        while (result.hasPendingPlan()) {
            String confirm = reader.readLine("🔄 重新规划已完成，是否执行新计划? [y/n]: ").trim();
            if (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes")) {
                renderer.println("\n🚀 执行重新规划的计划...\n");
                result = planAgent.executePlan(result.pendingPlan(), renderer);
            } else {
                renderer.println("⏭ 已取消执行重新规划的计划\n");
                break;
            }
        }
    }
}
