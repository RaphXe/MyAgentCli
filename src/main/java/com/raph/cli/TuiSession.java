package com.raph.cli;

import com.raph.agent.Agent;
import com.raph.agent.AgentRuntime;
import com.raph.agent.PlanExecuteAgent;
import com.raph.hitl.TerminalHitlHandler;
import com.raph.interaction.InteractionException;
import com.raph.interaction.InteractionPort;
import com.raph.llm.LlmClientManager;
import com.raph.llm.LlmConfig;
import com.raph.memory.ContextUsage;
import com.raph.memory.MemoryManager;
import com.raph.mcp.MCPServerManager;
import com.raph.plan.ExecutionPlan;
import com.raph.plan.PlanView;
import com.raph.render.LightTuiRenderer;
import com.raph.render.RenderEvent;
import com.raph.render.Renderer;
import com.raph.render.ViewAwareRenderer;
import com.raph.render.inline.InlineRenderer;
import com.raph.skill.Skill;
import com.raph.skill.SkillRepository;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class TuiSession {
    private static final String PROMPT_CONFIRM = "❓ 是否执行此计划? [y/n]: ";
    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final InteractionPort interaction;
    private final Renderer renderer;
    private final LlmClientManager llmClient;
    private final ToolRegistry toolRegistry;
    private final TerminalHitlHandler hitlHandler;
    private final MemoryManager memoryManager;
    private final MCPServerManager mcpServerManager;
    private final Agent agent;
    private final PlanExecuteAgent planAgent;
    private AgentRuntime teamRuntime;
    private PlanView lastPlanView;
    private SessionMode mode = SessionMode.NORMAL;
    private boolean exitRequested;

    public TuiSession(InteractionPort interaction,
                      Renderer renderer,
                      LlmClientManager llmClient,
                      ToolRegistry toolRegistry,
                      TerminalHitlHandler hitlHandler,
                      MemoryManager memoryManager,
                      MCPServerManager mcpServerManager,
                      Agent agent,
                      PlanExecuteAgent planAgent) {
        this.interaction = interaction;
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
            refreshRendererView();
            renderer.emit(RenderEvent.status(TuiStatusLine.contextBar(currentContextUsage())));

            String line;
            try {
                renderer.beforeInput();
                line = interaction.readLine(renderer.inputPrompt(mode.prompt()), renderer.inputRightPrompt());
            } catch (InteractionException e) {
                if (e.type() == InteractionException.Type.INTERRUPTED) {
                    renderer.println("\n已取消当前输入。输入 /exit 可退出。\n");
                    continue;
                }
                renderer.println("\n输入流已关闭，正在退出。\n");
                break;
            } finally {
                renderer.afterInput();
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
            case HELP -> handleHelpCommand(command.arguments());
            case STATUS -> handleStatusCommand();
            case TOOLS -> handleToolsCommand(command.arguments());
            case LOGS -> handleLogsCommand(command.arguments());
            case THEME -> handleThemeCommand(command.arguments());
            case COMPACT -> handleCompactCommand();
            case PLAN -> handlePlanCommand(command);
            case TEAM -> handleTeamCommand(command);
            case MCP -> handleMcpCommand(command.arguments());
            case SKILLS -> handleSkillsCommand(command.arguments());
            case HITL -> handleHitlCommand(command.arguments());
            case CONNECT -> handleConnectCommand(command.arguments());
            case MODEL -> handleModelCommand();
            case SAVE -> saveMemory(command.arguments());
            case CLEAR -> clearHistory();
            case EXIT -> exitOrReturnToNormal();
            case UNKNOWN -> {
                renderer.println("❌ 未知命令: " + command.rawInput() + "\n");
                handleHelpCommand("");
            }
            case USER_INPUT -> {
            }
        }
    }

    private void handleHelpCommand(String arguments) {
        String topic = arguments == null ? "" : arguments.trim().toLowerCase(Locale.ROOT);
        if ("mcp".equals(topic)) {
            renderer.println("""
                    MCP 命令:
                    /mcp                         查看 server 状态
                    /mcp logs <name>             查看某个 server 日志
                    /mcp restart <name>          重启 server
                    /mcp disable <name>          禁用 server 并卸载其工具
                    /mcp enable <name>           启用并加载 server

                    提示: /status 会显示 MCP 总览，/tools 可查看已注册工具。
                    """);
            return;
        }
        if ("tools".equals(topic)) {
            renderer.println("""
                    工具命令:
                    /tools                       查看全部工具摘要
                    /tools <keyword>             按名称、描述或风险说明过滤
                    /logs tools                  查看最近工具调用审计
                    /logs tools <n>              查看最近 n 条工具调用审计
                    """);
            return;
        }
        renderer.println("""
                可用命令:
                /help [topic]                  查看帮助，topic 支持 mcp/tools
                /status                        查看模型、模式、上下文、MCP 与记忆状态
                /tools [keyword]               查看或过滤当前可用工具
                /logs [tools [n]|mcp <name>]   查看工具审计或 MCP 日志
                /theme [light|compact]         查看或切换 TUI 显示密度
                /compact                       手动压缩普通模式对话上下文
                /connect <api_base>            连接 OpenAI-compatible provider
                /model                         获取并切换模型
                /plan [task]                   切换计划模式，或直接规划任务
                /team [task]                   切换团队模式，或直接运行 Multi-Agent
                /mcp                           查看/管理 MCP server
                /skills                        查看/重载/展示 skills
                /hitl [on|off]                 查看或切换人工审批
                /save <描述>                   保存长期记忆
                /clear                         清空当前模式会话状态
                /exit                          退出当前模式，普通模式下退出程序
                """);
    }

    private void handleStatusCommand() {
        refreshRendererView();
        ContextUsage usage = currentContextUsage();
        StringBuilder sb = new StringBuilder();
        sb.append("Status:\n");
        sb.append("- mode: ").append(mode.displayName()).append("\n");
        sb.append("- llm: ").append(llmClient.isConnected() ? llmClient.status() : "未连接").append("\n");
        sb.append("- hitl: ").append(hitlHandler.isEnabled() ? "on" : "off").append("\n");
        sb.append("- context: ").append(TuiStatusLine.contextBar(usage).trim()).append("\n");
        sb.append("- memories: ").append(memoryManager.getLongTermHistory().getAllEntries().size()).append("\n");
        if (memoryManager.getLastWarning() != null && !memoryManager.getLastWarning().isBlank()) {
            sb.append("- memoryWarning: ").append(memoryManager.getLastWarning()).append("\n");
        }
        if (lastPlanView != null) {
            sb.append("- lastPlan: ").append(lastPlanView.status())
                    .append(" tasks ").append(lastPlanView.completedTasks())
                    .append("/").append(lastPlanView.totalTasks())
                    .append(" failed ").append(lastPlanView.failedTasks())
                    .append("\n");
        }
        if (teamRuntime != null) {
            var teamView = teamRuntime.currentTeamView();
            sb.append("- team: ").append(teamView.status())
                    .append(" round ").append(teamView.currentRound()).append("/").append(teamView.maxRounds())
                    .append(" steps ").append(teamView.agentSteps()).append("/").append(teamView.maxAgentSteps())
                    .append(" active ").append(teamView.activeTasks())
                    .append("\n");
        }
        sb.append("\nMCP:\n").append(mcpServerManager.statusReport());
        renderer.println(sb.append("\n").toString());
    }

    private void handleToolsCommand(String arguments) {
        String query = arguments == null ? "" : arguments.trim().toLowerCase(Locale.ROOT);
        List<ToolRegistry.ToolSummary> tools = toolRegistry.toolSummaries().stream()
                .filter(tool -> query.isBlank() || toolMatches(tool, query))
                .sorted(Comparator.comparing(ToolRegistry.ToolSummary::requiresApproval)
                        .thenComparing(ToolRegistry.ToolSummary::name))
                .toList();
        if (tools.isEmpty()) {
            renderer.println("Tools: 未找到匹配项" + (query.isBlank() ? "" : " query=" + query) + "\n");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Tools");
        if (!query.isBlank()) {
            sb.append(" query=").append(query);
        }
        sb.append(" (").append(tools.size()).append("):\n");
        int limit = Math.min(tools.size(), 80);
        for (int i = 0; i < limit; i++) {
            ToolRegistry.ToolSummary tool = tools.get(i);
            sb.append("- ").append(tool.name())
                    .append(" [").append(blankToDefault(tool.dangerLevel(), "未知")).append("]");
            if (tool.requiresApproval()) {
                sb.append(" approval");
            }
            if (tool.mutatesFile()) {
                sb.append(" mutates-file");
            }
            if (tool.unknownRisk()) {
                sb.append(" unknown-risk");
            }
            sb.append("\n  ").append(tool.description()).append("\n");
        }
        if (tools.size() > limit) {
            sb.append("... 已截断，使用 /tools <keyword> 继续过滤\n");
        }
        renderer.println(sb.append("\n").toString());
    }

    private boolean toolMatches(ToolRegistry.ToolSummary tool, String query) {
        return contains(tool.name(), query)
                || contains(tool.description(), query)
                || contains(tool.dangerLevel(), query)
                || contains(tool.riskDescription(), query);
    }

    private void handleLogsCommand(String arguments) {
        String args = arguments == null ? "" : arguments.trim();
        if (args.isEmpty() || args.equalsIgnoreCase("tools")) {
            renderToolLogs(20);
            return;
        }

        String[] parts = args.split("\\s+", 3);
        String target = parts[0].toLowerCase(Locale.ROOT);
        if ("tools".equals(target)) {
            renderToolLogs(parts.length > 1 ? parsePositiveInt(parts[1], 20) : 20);
            return;
        }
        if ("mcp".equals(target)) {
            if (parts.length < 2 || parts[1].isBlank()) {
                renderer.println("❌ 用法: /logs mcp <server>\n");
                return;
            }
            renderer.println(mcpServerManager.logs(parts[1]));
            return;
        }
        renderer.println("""
                ❌ 用法:
                /logs
                /logs tools [n]
                /logs mcp <server>
                """);
    }

    private void renderToolLogs(int limit) {
        List<ToolRegistry.ToolAuditEvent> events = toolRegistry.recentAuditEvents();
        if (events.isEmpty()) {
            renderer.println("Tool logs: (empty)\n");
            return;
        }
        int count = Math.min(Math.max(1, limit), events.size());
        StringBuilder sb = new StringBuilder("Tool logs: last ").append(count).append("\n");
        List<ToolRegistry.ToolAuditEvent> slice = events.subList(events.size() - count, events.size());
        for (ToolRegistry.ToolAuditEvent event : slice) {
            sb.append("- ").append(LOG_TIME_FORMAT.format(event.timestamp()))
                    .append(" ").append(event.toolName())
                    .append(" ").append(event.elapsedMillis()).append("ms\n")
                    .append("  args: ").append(event.argumentsSummary()).append("\n")
                    .append("  result: ").append(event.resultSummary()).append("\n");
        }
        renderer.println(sb.append("\n").toString());
    }

    private void handleThemeCommand(String arguments) {
        String value = arguments == null ? "" : arguments.trim();
        if (!(renderer instanceof LightTuiRenderer lightRenderer)) {
            renderer.println("Theme: plain renderer 当前不支持运行时主题切换。可设置 PAICLI_TUI_MODE=light 后重启启用轻量 TUI。\n");
            return;
        }
        if (value.isBlank()) {
            renderer.println("Theme: " + lightRenderer.theme().name().toLowerCase(Locale.ROOT)
                    + "\n可选: /theme light 或 /theme compact\n");
            return;
        }
        if (!lightRenderer.setTheme(value)) {
            renderer.println("❌ 未知主题: " + value + "\n可选: light, compact\n");
            return;
        }
        renderer.println("✅ Theme 已切换: " + lightRenderer.theme().name().toLowerCase(Locale.ROOT) + "\n");
    }

    private void handleCompactCommand() {
        if (mode != SessionMode.NORMAL) {
            renderer.println("⚠ /compact 当前只压缩普通模式对话；计划/团队模式会按各自执行上下文管理。\n");
            return;
        }
        renderer.emit(RenderEvent.activity("memory", "正在压缩上下文..."));
        try {
            MemoryManager.CompactResult result = memoryManager.compactConversation(agent);
            renderer.println((result.compacted() ? "✅ " : "ℹ ") + result.message() + "\n");
        } catch (IOException e) {
            renderer.println("❌ 上下文压缩失败: " + e.getMessage() + "\n");
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

    private void handlePlanCommand(TuiCommand command) {
        if (command.hasArguments()) {
            if (mode != SessionMode.PLAN) {
                mode = SessionMode.PLAN;
            }
            handlePlanInput(command.arguments());
            return;
        }
        switchMode(SessionMode.PLAN, "🧠 当前处于计划模式，输入任务目标后我将制定执行计划");
    }

    private void handleNormalInput(String input) {
        renderer.beginTurn();
        renderer.emit(RenderEvent.activity("agent", "🤔 思考中..."));
        String response;
        Renderer.StreamHandle streamRenderer = renderer.contentStream("🤖 Agent: ");
        try {
            response = agent.run(input, streamRenderer);
        } catch (IOException e) {
            renderer.emit(RenderEvent.error("agent", "\n❌ Agent 执行失败: " + e.getMessage() + "\n"));
            return;
        }
        if (streamRenderer.hasContent()) {
            renderer.println("");
        } else {
            renderer.print("\r              \r");
            renderer.println("🤖 Agent: " + response);
        }
        renderer.emit(RenderEvent.tokenUsage(TuiStatusLine.tokenSummary(agent),
                java.util.Map.of(
                        "input", String.valueOf(agent.getLastInputTokens()),
                        "output", String.valueOf(agent.getLastOutputTokens()),
                        "total", String.valueOf(agent.getLastTotalTokens()),
                        "context", String.valueOf(agent.getContextTokens())
                )));
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
            apiKey = interaction.readSecret("🔑 API key: ").trim();
        } catch (InteractionException e) {
            renderer.println(e.type() == InteractionException.Type.INTERRUPTED
                    ? "\n已取消连接。\n"
                    : "\n输入流已关闭，无法连接。\n");
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
            return interaction.readLine("请选择模型编号或输入模型名: ").trim();
        } catch (InteractionException e) {
            renderer.println(e.type() == InteractionException.Type.INTERRUPTED
                    ? "\n已取消模型选择。\n"
                    : "\n输入流已关闭，无法选择模型。\n");
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
        if (renderer instanceof InlineRenderer inlineRenderer) {
            inlineRenderer.clearBlocks();
        }
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
        renderer.beginTurn();
        renderer.println("👥 启动自治 Multi-Agent 协作...\n");
        try {
            String response = teamRuntime().run(teamTask);
            refreshRendererView();
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
        renderer.beginTurn();
        renderer.emit(RenderEvent.activity("plan", "🤔 规划中..."));
        try {
            ExecutionPlan plan = planAgent.createPlan(input);
            renderer.print("\r              \r");
            String formattedPlan = planAgent.formatPlan(plan);
            lastPlanView = planAgent.planView(plan);
            refreshRendererView();
            renderer.emit(RenderEvent.planCreated(plan.getGoal(), formattedPlan)
                    .withMetadata("goal", lastPlanView.goal())
                    .withMetadata("summary", lastPlanView.summary())
                    .withMetadata("tasks", String.valueOf(lastPlanView.totalTasks())));
            renderer.println(formattedPlan);

            String confirm = interaction.readLine(PROMPT_CONFIRM).trim();
            if (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes")) {
                runPlanExecutionLoop(plan);
            } else {
                renderer.println("⏭ 已取消执行\n");
            }
        } catch (IOException e) {
            renderer.print("\r              \r");
            renderer.println("❌ 计划创建失败: " + e.getMessage() + "\n");
        } catch (InteractionException e) {
            renderer.print("\r              \r");
            renderer.println(e.type() == InteractionException.Type.INTERRUPTED
                    ? "⏭ 已取消执行\n"
                    : "输入流已关闭，无法确认计划。\n");
        }
    }

    private void runPlanExecutionLoop(ExecutionPlan plan) {
        renderer.println("\n🚀 用户确认，开始执行计划...\n");
        PlanExecuteAgent.ExecutionResult result = planAgent.executePlan(plan, renderer);
        lastPlanView = planAgent.planView(plan);
        refreshRendererView();

        while (result.hasPendingPlan()) {
            String confirm;
            try {
                confirm = interaction.readLine("🔄 重新规划已完成，是否执行新计划? [y/n]: ").trim();
            } catch (InteractionException e) {
                renderer.println(e.type() == InteractionException.Type.INTERRUPTED
                        ? "⏭ 已取消执行重新规划的计划\n"
                        : "输入流已关闭，无法确认重新规划。\n");
                break;
            }
            if (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes")) {
                renderer.println("\n🚀 执行重新规划的计划...\n");
                ExecutionPlan pendingPlan = result.pendingPlan();
                result = planAgent.executePlan(pendingPlan, renderer);
                lastPlanView = planAgent.planView(pendingPlan);
                refreshRendererView();
            } else {
                renderer.println("⏭ 已取消执行重新规划的计划\n");
                break;
            }
        }
    }

    private static boolean contains(String value, String query) {
        return value != null && query != null
                && value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static int parsePositiveInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private void refreshRendererView() {
        if (!(renderer instanceof ViewAwareRenderer viewAwareRenderer)) {
            return;
        }
        if (lastPlanView != null) {
            viewAwareRenderer.updatePlanView(lastPlanView);
        }
        if (teamRuntime != null) {
            viewAwareRenderer.updateTeamView(teamRuntime.currentTeamView());
        }
    }
}
