package com.raph;

import com.raph.agent.Agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        printBanner();

        // 加载 API Key
        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 错误: 未找到 API_KEY");
            System.exit(1);
        }

        // 创建 Agent
        Agent agent = new Agent(apiKey);

        // 交互式循环
        Scanner scanner = new Scanner(System.in);
        System.out.println("💡 提示: 输入 'clear' 清空历史, 'exit' 退出\n");

        while (true) {
            System.out.print("👤 你: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;
            if (input.equalsIgnoreCase("exit")) break;
            if (input.equalsIgnoreCase("clear")) {
                agent.clearHistory();
                System.out.println("🗑️ 历史已清空\n");
                continue;
            }

            // 运行 Agent

            String response = null;
            try {
                response = agent.run(input);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("🤖 Agent: " + response + "\n");
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

    private static String loadApiKey() {
        // 先尝试从当前目录读取 .env
        File envFile = new File(".env");
        if (envFile.exists()) {
            String apiKey = readApiKeyFromFile(envFile);
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey;
            }
        }

        // 再尝试从环境变量读取
        return readApiKeyFromEnvironment();
    }

    private static String readApiKeyFromEnvironment() {
        String[] names = {"GLM_API_KEY", "DEEPSEEK_API_KEY", "OPENAI_API_KEY", "API_KEY"};
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String readApiKeyFromFile(File envFile) {
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

                String key = trimmed.substring(0, separatorIndex).trim();
                if (!"GLM_API_KEY".equals(key)
                        && !"DEEPSEEK_API_KEY".equals(key)
                        && !"OPENAI_API_KEY".equals(key)
                        && !"API_KEY".equals(key)) {
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
}
