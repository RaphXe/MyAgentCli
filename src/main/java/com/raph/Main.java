package com.raph;

import com.raph.agent.Agent;
import com.raph.agent.PlanExecuteAgent;
import com.raph.llm.DeepSeekClient;
import com.raph.llm.LlmClient;
import com.raph.tool.ToolRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Scanner;

public class Main {

    private static final String PROMPT_NORMAL = "👤 你: ";
    private static final String PROMPT_PLAN = "🧠 计划模式 > ";
    private static final int DEFAULT_OUTPUT_TRUNCATE_LIMIT = 2000;

    public static void main(String[] args) {
        printBanner();

        // 加载 API Key
        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 错误: 未找到 API_KEY");
            System.exit(1);
        }

        // 创建共享组件
        LlmClient llmClient = new DeepSeekClient(apiKey);
        ToolRegistry toolRegistry = new ToolRegistry();

        // 加载输出截断限制
        int truncateLimit = loadOutputTruncateLimit();

        // 创建 Agent 和 PlanExecuteAgent
        Agent agent = new Agent(apiKey);
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, toolRegistry, truncateLimit);

        // 交互式循环
        Scanner scanner = new Scanner(System.in);
        System.out.println("💡 提示: 输入 '/plan' 进入计划模式, 'clear' 清空历史, 'exit' 退出\n");

        boolean planMode = false;

        while (true) {
            System.out.print(planMode ? PROMPT_PLAN : PROMPT_NORMAL);
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            // 全局命令: exit
            if (input.equalsIgnoreCase("exit")) {
                if (planMode) {
                    planMode = false;
                    System.out.println("🚪 已退出计划模式\n");
                    continue;
                }
                break;
            }

            // /plan 命令: 切换计划模式
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

            // 普通模式下: clear
            if (!planMode && input.equalsIgnoreCase("clear")) {
                agent.clearHistory();
                System.out.println("🗑️ 历史已清空\n");
                continue;
            }

            // 根据模式路由
            if (planMode) {
                // 计划模式
                System.out.print("🤔 规划中...");
                System.out.flush();
                String response = planAgent.run(input);
                System.out.print("\r              \r"); // 清除思考提示
                System.out.println(response);
            } else {
                // 普通 Agent 模式
                System.out.print("🤔 思考中...");
                System.out.flush();
                String response = null;
                try {
                    response = agent.run(input);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                System.out.print("\r              \r"); // 清除思考提示
                System.out.println("🤖 Agent: " + response);
                System.out.printf("📊 Token 消耗: 输入 %d + 输出 %d = 总计 %d%n%n",
                        agent.getLastInputTokens(),
                        agent.getLastOutputTokens(),
                        agent.getLastTotalTokens());
            }
        }
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
