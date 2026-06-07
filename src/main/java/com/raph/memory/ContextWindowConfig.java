package com.raph.memory;

public final class ContextWindowConfig {
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 1_048_576;

    private ContextWindowConfig() {
    }

    public static int loadMaxContextTokens() {
        String value = System.getenv("CONTEXT_WINDOW_SIZE");
        if (value != null && !value.isBlank()) {
            try {
                int v = Integer.parseInt(value.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_MAX_CONTEXT_TOKENS;
    }
}
