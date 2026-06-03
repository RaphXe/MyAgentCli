package com.raph;

import com.raph.agent.Agent;
import com.raph.agent.AgentRuntime;
import com.raph.agent.PlanExecuteAgent;
import com.raph.llm.DeepSeekClient;
import com.raph.llm.LlmClient;
import com.raph.memory.MemoryManager;
import com.raph.plan.ExecutionPlan;
import com.raph.tool.ToolRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class Main {

    private static final String PROMPT_NORMAL = "👤 你: ";
    private static final String PROMPT_PLAN = "🧠 计划模式 > ";
    private static final String PROMPT_CONFIRM = "❓ 是否执行此计划? [y/n]: ";
    private static final int DEFAULT_OUTPUT_TRUNCATE_LIMIT = 2000;

    public static void main(String[] args) {
        printBanner();

        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 错误: 未找到 API_KEY");
            System.exit(1);
        }

        LlmClient llmClient = new DeepSeekClient(apiKey);
        ToolRegistry toolRegistry = new ToolRegistry();

        int truncateLimit = loadOutputTruncateLimit();

        MemoryManager memoryManager = new MemoryManager(llmClient);
        memoryManager.init();

        Agent agent = new Agent(llmClient, memoryManager);
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, toolRegistry, truncateLimit);

        LineReader reader;
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();
        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("💡 提示: 输入 '/plan' 进入计划模式, '/team <任务>' 使用自治多 Agent, '/save [描述]' 保存记忆, 'clear' 清空历史, 'exit' 退出\n");

        boolean planMode = false;

        while (true) {
            String prompt = planMode ? PROMPT_PLAN : PROMPT_NORMAL;

            if (!planMode) {
                System.out.print(contextBar(memoryManager, agent));
            }

            String input = reader.readLine(prompt).trim();

            if (input.isEmpty()) continue;

            if (input.equalsIgnoreCase("exit")) {
                if (planMode) {
                    planMode = false;
                    System.out.println("🚪 已退出计划模式\n");
                    continue;
                }
                break;
            }

            if (input.equalsIgnoreCase("/plan")) {
                planMode = !planMode;
                if (planMode) {
                    System.out.println("🧠 当前处于计划模式 — 输入你的任务目标，我将为你制定执行计划");
                    System.out.println("   (输入 '/plan' 或 'exit' 退出计划模式)\n");
                } else {
                    System.out.println("🚪 已退出计划模式\n");
                }
                continue;
            }

            if (!planMode && input.toLowerCase().startsWith("/team ")) {
                String teamTask = input.substring(6).trim();
                if (teamTask.isEmpty()) {
                    System.out.println("❌ 用法: /team <任务内容>\n");
                    continue;
                }
                System.out.println("👥 启动自治 Multi-Agent 协作...\n");
                try {
                    AgentRuntime runtime = new AgentRuntime(llmClient, toolRegistry);
                    String response = runtime.run(teamTask);
                    System.out.println(response);
                } catch (IOException e) {
                    System.out.println("❌ 多 Agent 执行失败: " + e.getMessage() + "\n");
                }
                continue;
            }

            if (!planMode && input.equalsIgnoreCase("clear")) {
                agent.clearHistory();
                System.out.println("🗑️ 历史已清空\n");
                continue;
            }

            if (!planMode && input.toLowerCase().startsWith("/save")) {
                String description = input.substring(5).trim();
                if (description.isEmpty()) {
                    System.out.println("❌ 用法: /save <描述内容>\n");
                    continue;
                }
                try {
                    memoryManager.saveToMemory(description, agent);
                    System.out.println();
                } catch (IOException e) {
                    System.out.println("❌ 保存记忆失败: " + e.getMessage() + "\n");
                }
                continue;
            }

            if (planMode) {
                System.out.print("🤔 规划中...");
                System.out.flush();
                try {
                    ExecutionPlan plan = planAgent.createPlan(input);
                    System.out.print("\r              \r");
                    System.out.println(planAgent.formatPlan(plan));

                    String confirm = reader.readLine(PROMPT_CONFIRM).trim();
                    if (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes")) {
                        runPlanExecutionLoop(planAgent, plan, reader);
                    } else {
                        System.out.println("⏭ 已取消执行\n");
                    }
                } catch (IOException e) {
                    System.out.print("\r              \r");
                    System.out.println("❌ 计划创建失败: " + e.getMessage() + "\n");
                }
            } else {
                System.out.print("🤔 思考中...");
                System.out.flush();
                String response = null;
                try {
                    response = agent.run(input);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                System.out.print("\r              \r");
                System.out.println("🤖 Agent: " + response);
                System.out.printf("📊 Token 消耗: 输入 %d + 输出 %d = 总计 %d | 上下文: %s/%s (%.1f%%)%n%n",
                        agent.getLastInputTokens(),
                        agent.getLastOutputTokens(),
                        agent.getLastTotalTokens(),
                        formatTokens(agent.getContextTokens()),
                        formatTokens(agent.getMaxContextTokens()),
                        agent.getContextUsagePercent());
            }
        }
    }

    private static void runPlanExecutionLoop(PlanExecuteAgent planAgent, ExecutionPlan plan,
                                              LineReader reader) {
        System.out.println("\n🚀 用户确认，开始执行计划...\n");
        PlanExecuteAgent.ExecutionResult result = planAgent.executePlan(plan);
        System.out.println(result.output());

        while (result.hasPendingPlan()) {
            String confirm = reader.readLine("🔄 重新规划已完成，是否执行新计划? [y/n]: ").trim();
            if (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes")) {
                System.out.println("\n🚀 执行重新规划的计划...\n");
                result = planAgent.executePlan(result.pendingPlan());
                System.out.println(result.output());
            } else {
                System.out.println("⏭ 已取消执行重新规划的计划\n");
                break;
            }
        }
    }

    private static String contextBar(MemoryManager memoryManager, Agent agent) {
        int used = memoryManager.getTokenBudget().total();
        int max = memoryManager.getTokenBudget().getMaxContextTokens();
        double pct = memoryManager.getTokenBudget().usagePercent();

        int barWidth = 20;
        int filled = (int) Math.round(pct / 100.0 * barWidth);
        if (filled > barWidth) filled = barWidth;

        StringBuilder bar = new StringBuilder("📊 [");
        for (int i = 0; i < barWidth; i++) {
            if (i < filled) {
                bar.append(pct > 80 ? "█" : "▓");
            } else {
                bar.append("░");
            }
        }
        bar.append("] ");

        bar.append(formatTokens(used)).append("/").append(formatTokens(max));
        bar.append(String.format(" %.1f%%", pct));
        bar.append("\n");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    private static void printBanner() {
        System.out.println("""
        ╔══════════════════════════════════════════════════════════╗
        ║   ██████╗  █████╗ ██╗      ██████╗██╗     ██╗            ║
        ║   ██╔══██╗██╔══██╗██║     ██╔════╝██║     ██║            ║
        ║   ██████╔╝███████║██║     ██║     ██║     ██║            ║
        ║   ██╔═══╝ ██╔══██║██║     ██║     ██║     ██║            ║
        ║   ██║     ██║  ██║███████╗╚██████╗███████╗██║            ║
        ║   ╚═╝     ╚═╝  ╚═╝╚══════╝ ╚═════╝╚══════╝╚═╝            ║
        ║              简单的 Java Agent CLI v1.0.0                ║
        ╚══════════════════════════════════════════════════════════╝
        """);
    }

    /**
     * 从 .env 文件和环境变量中读取配置值（.env 优先）
     *
     * @param keys 支持的变量名列表，按优先级排列
     * @return 读取到的值，未找到返回 null
     */
    private static String readEnvValue(String... keys) {
        // 先尝试从当前目录读取 .env
        File envFile = new File(".env");
        if (envFile.exists()) {
            String value = readValueFromFile(envFile, keys);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        // 再尝试从系统环境变量读取
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 从 .env 文件读取指定 key 的值
     */
    private static String readValueFromFile(File envFile, String... keys) {
        try {
            for (String line : Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int separatorIndex = trimmed.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }

                String lineKey = trimmed.substring(0, separatorIndex).trim();

                boolean matched = false;
                for (String key : keys) {
                    if (key.equals(lineKey)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    continue;
                }

                String value = trimmed.substring(separatorIndex + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }

                if (!value.isBlank()) {
                    return value;
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    private static String loadApiKey() {
        return readEnvValue("GLM_API_KEY", "DEEPSEEK_API_KEY", "OPENAI_API_KEY", "API_KEY");
    }

    /**
     * 加载输出截断限制（默认 2000 字符）
     * 支持 PAICLI_OUTPUT_TRUNCATE_LIMIT 或 OUTPUT_TRUNCATE_LIMIT
     */
    private static int loadOutputTruncateLimit() {
        String value = readEnvValue("PAICLI_OUTPUT_TRUNCATE_LIMIT", "OUTPUT_TRUNCATE_LIMIT");
        if (value != null && !value.isBlank()) {
            try {
                int limit = Integer.parseInt(value.trim());
                if (limit > 0) {
                    return limit;
                }
            } catch (NumberFormatException e) {
                System.err.println("⚠ 无效的输出截断限制值: " + value + "，使用默认值 " + DEFAULT_OUTPUT_TRUNCATE_LIMIT);
            }
        }
        return DEFAULT_OUTPUT_TRUNCATE_LIMIT;
    }
}
