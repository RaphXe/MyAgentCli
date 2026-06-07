package com.raph.llm;

public record LlmConfig(String provider, String baseUrl, String apiKey, String model) {
    public static final String OPENAI_COMPATIBLE = "openai-compatible";

    public LlmConfig {
        provider = blankToDefault(provider, OPENAI_COMPATIBLE);
        baseUrl = trimToNull(baseUrl);
        apiKey = trimToNull(apiKey);
        model = trimToNull(model);
    }

    public boolean isComplete() {
        return baseUrl != null && apiKey != null && model != null;
    }

    public LlmConfig withModel(String selectedModel) {
        return new LlmConfig(provider, baseUrl, apiKey, selectedModel);
    }

    private static String blankToDefault(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
