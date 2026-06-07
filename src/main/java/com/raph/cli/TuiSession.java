package com.raph.cli;

import com.raph.agent.Agent;
import com.raph.agent.AgentRuntime;
import com.raph.agent.PlanExecuteAgent;
import com.raph.hitl.TerminalHitlHandler;
import com.raph.llm.LlmClientManager;
import com.raph.llm.LlmConfig;
import com.raph.memory.ContextUsage;
import com.raph.memory.MemoryManager;
import com.raph.mcp.MCPServerManager;
import com.raph.plan.ExecutionPlan;
import com.raph.render.Renderer;
import com.raph.skill.Skill;
import com.raph.skill.SkillRepository;
import com.raph.tool.ToolRegistry;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.IOException;
import java.util.List;

public class TuiSession {
    private static final String PROMPT_CONFIRM = "❓ 是否执行此计划? [y/n]: ";

    private final LineReader reader;
    private final Renderer renderer;
    private final LlmClientManager llmClient;
    private final ToolRegistry toolRegistry;
    private final TerminalHitlHandler hitlHandler;
    private final MemoryManager memoryManager;
    private final MCPServerManager mcpServerManager;
    private final Agent agent;
    private final PlanExecuteAgent planAgent;
    private AgentRuntime teamRuntime;
    private SessionMode mode = SessionMode.NORMAL;
    private boolean exitRequested;

    public TuiSession(LineReader reader,
                      Renderer renderer,
                      LlmClientManager llmClient,
                      ToolRegistry toolRegistry,
                      TerminalHitlHandler hitlHandler,
                      MemoryManager memoryManager,
                      MCPServerManager mcpServerManager,
                      Agent agent,
                      PlanExecuteAgent planAgent) {
        this.reader = reader;
        this.renderer = renderer;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.hitlHandler = hitlHandler;
        this.memoryManager = memoryManager;
        this.mcpServerManager = mcpServerManager;
        this.agent = agent;
        this.planAgent = planAgent;
    }

    public void run() {
        renderer.println("💡 提示: 输入 '/connect <api_base>' 连接模型, '/model' 切换模型, '/plan' 切换计划模式, '/team' 切换团队模式, '/mcp' 查看 MCP, '/skills' 查看 skill, '/hitl on|off' 切换人工审批, '/save <描述>' 保存记忆, '/clear' 清空历史, '/exit' 退出或返回普通模式\n");

        while (true) {
            renderer.print(TuiStatusLine.contextBar(currentContextUsage()));

            String line;
            try {
                line = reader.readLine(mode.prompt());
            } catch (UserInterruptException e) {
                renderer.println("\n已取消当前输入。输入 /exit 可退出。\n");
                continue;
            } catch (EndOfFileException e) {
                renderer.println("\n输入流已关闭，正在退出。\n");
                break;
            }
            if (line == null) {
                renderer.println("\n输入流已关闭，正在退出。\n");
                break;
            }
            String input = line.trim();
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
            case MCP -> handleMcpCommand(command.arguments());
            case SKILLS -> handleSkillsCommand(command.arguments());
            case HITL -> handleHitlCommand(command.arguments());
            case CONNECT -> handleConnectCommand(command.arguments());
            case MODEL -> handleModelCommand();
            case SAVE -> saveMemory(command.arguments());
            case CLEAR -> clearHistory();
            case EXIT -> exitOrReturnToNormal();
            case UNKNOWN -> renderer.println("❌ 未知命令: " + command.rawInput() + "\n");
            case USER_INPUT -> {
            }
        }
    }

    private void handleSkillsCommand(String arguments) {
        String args = arguments == null ? "" : arguments.trim();
        SkillRepository repository = SkillRepository.defaultRepository();
        if (args.isEmpty()) {
            StringBuilder sb = new StringBuilder("Skills:\n");
            for (Skill skill : repository.all()) {
                sb.append("- ").append(skill.id())
                        .append(skill.description().isBlank() ? "" : " - " + skill.description())
                        .append(" [").append(skill.source()).append("]\n");
            }
            renderer.println(sb.append("\n").toString());
            return;
        }

        String[] parts = args.split("\\s+", 2);
        String action = parts[0].toLowerCase(java.util.Locale.ROOT);
        String id = parts.length > 1 ? parts[1].trim() : "";
        switch (action) {
            case "show" -> {
                if (id.isEmpty()) {
                    renderer.println("❌ 用法: /skills show <id>\n");
                    return;
                }
                Skill skill = repository.find(id);
                if (skill == null) {
                    renderer.println("❌ 未找到 skill: " + id + "\n");
                    return;
                }
                renderer.println("Skill: " + skill.id() + "\nsource=" + skill.source()
                        + "\n\n" + skill.content() + "\n");
            }
            case "reload" -> {
                SkillRepository reloaded = SkillRepository.reloadDefault();
                renderer.println("✅ skills 已重新加载: " + reloaded.all().size() + "\n");
            }
            default -> renderer.println("""
                    ❌ 用法:
                    /skills
                    /skills show <id>
                    /skills reload
                    """);
        }
    }

    private void handleMcpCommand(String arguments) {
        String args = arguments == null ? "" : arguments.trim();
        if (args.isEmpty()) {
            renderer.println(mcpServerManager.statusReport());
            return;
        }
        String[] parts = args.split("\\s+", 2);
        String action = parts[0].toLowerCase(java.util.Locale.ROOT);
        String name = parts.length > 1 ? parts[1].trim() : "";
        switch (action) {
            case "restart" -> {
                if (name.isEmpty()) {
                    renderer.println("❌ 用法: /mcp restart <name>\n");
                } else {
                    renderer.println(mcpServerManager.restart(name));
                }
            }
            case "logs" -> {
                if (name.isEmpty()) {
                    renderer.println("❌ 用法: /mcp logs <name>\n");
                } else {
                    renderer.println(mcpServerManager.logs(name));
                }
            }
            case "disable" -> {
                if (name.isEmpty()) {
                    renderer.println("❌ 用法: /mcp disable <name>\n");
                } else {
                    renderer.println(mcpServerManager.disable(name));
                }
            }
            case "enable" -> {
                if (name.isEmpty()) {
                    renderer.println("❌ 用法: /mcp enable <name>\n");
                } else {
                    renderer.println(mcpServerManager.enable(name));
                }
            }
            default -> renderer.println("""
                    ❌ 用法:
                    /mcp
                    /mcp restart <name>
                    /mcp logs <name>
                    /mcp disable <name>
                    /mcp enable <name>
                    """);
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
            renderer.println("\n❌ Agent 执行失败: " + e.getMessage() + "\n");
            return;
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

    private void handleConnectCommand(String arguments) {
        String baseUrl = normalizeConnectBaseUrl(arguments);
        if (baseUrl.isEmpty()) {
            renderer.println("❌ 用法: /connect <api_base>，例如 /connect https://api.openai.com/v1\n");
            return;
        }

        String apiKey;
        try {
            apiKey = reader.readLine("🔑 API key: ", '*').trim();
        } catch (UserInterruptException e) {
            renderer.println("\n已取消连接。\n");
            return;
        } catch (EndOfFileException e) {
            renderer.println("\n输入流已关闭，无法连接。\n");
            return;
        }
        if (apiKey.isEmpty()) {
            renderer.println("❌ API key 不能为空\n");
            return;
        }

        renderer.println("🔌 正在连接并获取模型列表...\n");
        try {
            List<String> models = llmClient.probeModels(baseUrl, apiKey);
            String selectedModel = chooseModel(models);
            if (selectedModel == null || selectedModel.isBlank()) {
                renderer.println("⏭ 已取消模型选择\n");
                return;
            }
            llmClient.connect(new LlmConfig(LlmConfig.OPENAI_COMPATIBLE, baseUrl, apiKey, selectedModel));
            renderer.println("✅ LLM 已连接: " + llmClient.status() + "\n");
        } catch (IOException | IllegalArgumentException e) {
            renderer.println("❌ 连接失败: " + e.getMessage() + "\n");
        }
    }

    private void handleModelCommand() {
        if (!llmClient.isConnected()) {
            renderer.println("❌ 尚未连接 LLM provider，请先使用 /connect <api_base>\n");
            return;
        }
        LlmConfig config = llmClient.config();
        renderer.println("🔎 正在获取模型列表...\n");
        try {
            List<String> models = llmClient.probeModels(config.baseUrl(), config.apiKey());
            String selectedModel = chooseModel(models);
            if (selectedModel == null || selectedModel.isBlank()) {
                renderer.println("⏭ 已取消模型选择\n");
                return;
            }
            llmClient.selectModel(selectedModel);
            renderer.println("✅ 当前模型: " + selectedModel + "\n");
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            renderer.println("❌ 模型列表获取失败: " + e.getMessage() + "\n");
        }
    }

    private String chooseModel(List<String> models) {
        if (models == null || models.isEmpty()) {
            renderer.println("⚠ 远端未返回模型列表，请手动输入模型名。\n");
            return readModelSelection();
        }

        StringBuilder sb = new StringBuilder("可用模型:\n");
        int limit = Math.min(models.size(), 80);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%2d. %s%n", i + 1, models.get(i)));
        }
        if (models.size() > limit) {
            sb.append("... 已截断，仍可直接输入完整模型名\n");
        }
        renderer.println(sb.toString());

        String selection = readModelSelection();
        if (selection == null || selection.isBlank()) {
            return null;
        }
        try {
            int index = Integer.parseInt(selection.trim());
            if (index >= 1 && index <= models.size()) {
                return models.get(index - 1);
            }
            renderer.println("❌ 模型编号超出范围\n");
            return null;
        } catch (NumberFormatException ignored) {
            return selection.trim();
        }
    }

    private String readModelSelection() {
        try {
            return reader.readLine("请选择模型编号或输入模型名: ").trim();
        } catch (UserInterruptException e) {
            renderer.println("\n已取消模型选择。\n");
            return null;
        } catch (EndOfFileException e) {
            renderer.println("\n输入流已关闭，无法选择模型。\n");
            return null;
        }
    }

    private String normalizeConnectBaseUrl(String arguments) {
        String value = arguments == null ? "" : arguments.trim();
        if (value.startsWith("+")) {
            value = value.substring(1).trim();
        }
        return value;
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
                memoryManager.clearSessionState();
                toolRegistry.clearSessionState();
                hitlHandler.clearApprovedAll();
                renderer.println("🗑️ 普通模式历史、token 统计和本次会话授权已清空\n");
            }
            case TEAM -> {
                teamRuntime = null;
                toolRegistry.clearSessionState();
                hitlHandler.clearApprovedAll();
                renderer.println("🗑️ 团队模式会话记忆已清空\n");
            }
            case PLAN -> {
                toolRegistry.clearSessionState();
                hitlHandler.clearApprovedAll();
                renderer.println("🗑️ 计划模式暂无持久会话历史，本次会话授权已清空\n");
            }
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
            MemoryManager.SaveMemoryResult result = memoryManager.saveToMemory(description, agent);
            renderer.println((result.saved() ? "✅ " : "❌ ") + result.message() + "\n");
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
