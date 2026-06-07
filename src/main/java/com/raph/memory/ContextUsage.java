package com.raph.memory;

public record ContextUsage(String label, int usedTokens, int maxTokens, String details) {
    public double usagePercent() {
        return maxTokens > 0 ? (double) usedTokens / maxTokens * 100.0 : 0.0;
    }
}
