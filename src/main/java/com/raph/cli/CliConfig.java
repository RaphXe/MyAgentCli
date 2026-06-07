package com.raph.cli;

import com.raph.llm.LlmConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * CLI 启动配置读取。
 */
public final class CliConfig {
    private static final int DEFAULT_OUTPUT_TRUNCATE_LIMIT = 2000;
    private static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-pro";

    private final LlmConfig llmConfig;
    private final int outputTruncateLimit;

    private CliConfig(LlmConfig llmConfig, int outputTruncateLimit) {
        this.llmConfig = llmConfig;
        this.outputTruncateLimit = outputTruncateLimit;
    }

    public static CliConfig load() {
        return new CliConfig(
                loadLlmConfig(),
                loadOutputTruncateLimit()
        );
    }

    public LlmConfig llmConfig() {
        return llmConfig;
    }

    public String apiKey() {
        return llmConfig == null ? null : llmConfig.apiKey();
    }

    public int outputTruncateLimit() {
        return outputTruncateLimit;
    }

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

    private static LlmConfig loadLlmConfig() {
        String apiKey = readEnvValue("PAICLI_LLM_API_KEY", "GLM_API_KEY", "DEEPSEEK_API_KEY", "OPENAI_API_KEY", "API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String baseUrl = readEnvValue("PAICLI_LLM_BASE_URL", "OPENAI_BASE_URL", "OPENAI_API_BASE");
        String model = readEnvValue("PAICLI_LLM_MODEL", "OPENAI_MODEL", "MODEL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_DEEPSEEK_BASE_URL;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_DEEPSEEK_MODEL;
        }
        return new LlmConfig(LlmConfig.OPENAI_COMPATIBLE, baseUrl, apiKey, model);
    }

    /**
     * 从 .env 文件和环境变量中读取配置值（.env 优先）。
     */
    private static String readEnvValue(String... keys) {
        File envFile = new File(".env");
        if (envFile.exists()) {
            String value = readValueFromFile(envFile, keys);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

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
                if (!matchesAny(lineKey, keys)) {
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

    private static boolean matchesAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
