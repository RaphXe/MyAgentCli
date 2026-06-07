package com.raph.cli;

import com.raph.agent.Agent;
import com.raph.memory.ContextUsage;
import com.raph.memory.MemoryManager;
import com.raph.memory.TokenBudget;

public final class TuiStatusLine {
    private static final int BAR_WIDTH = 20;

    private TuiStatusLine() {
    }

    public static ContextUsage normalContextUsage(MemoryManager memoryManager) {
        TokenBudget budget = memoryManager.getTokenBudget();
        String details = String.format("conv=%s, memory=%s, compressed=%s",
                formatTokens(budget.getCurrentConvTokens()),
                formatTokens(budget.getInjectedMemoryTokens()),
                formatTokens(budget.getCompressedHistoryTokens()));
        return new ContextUsage("普通模式", budget.total(), budget.getMaxContextTokens(), details);
    }

    public static String contextBar(ContextUsage usage) {
        int used = usage == null ? 0 : usage.usedTokens();
        int max = usage == null ? 0 : usage.maxTokens();
        double pct = usage == null ? 0.0 : usage.usagePercent();

        int filled = (int) Math.round(pct / 100.0 * BAR_WIDTH);
        if (filled > BAR_WIDTH) {
            filled = BAR_WIDTH;
        }

        StringBuilder bar = new StringBuilder("📊 ");
        if (usage != null && usage.label() != null && !usage.label().isBlank()) {
            bar.append(usage.label()).append(" ");
        }
        bar.append("[");
        for (int i = 0; i < BAR_WIDTH; i++) {
            if (i < filled) {
                bar.append(pct > 80 ? "█" : "▓");
            } else {
                bar.append("░");
            }
        }
        bar.append("] ");

        bar.append(formatTokens(used)).append("/").append(formatTokens(max));
        bar.append(String.format(" %.1f%%", pct));
        if (usage != null && usage.details() != null && !usage.details().isBlank()) {
            bar.append(" | ").append(usage.details());
        }
        bar.append("\n");
        return bar.toString();
    }

    public static String tokenSummary(Agent agent) {
        return String.format("📊 Token 消耗: 输入 %d + 输出 %d = 总计 %d | 上下文: %s/%s (%.1f%%)%n%n",
                agent.getLastInputTokens(),
                agent.getLastOutputTokens(),
                agent.getLastTotalTokens(),
                formatTokens(agent.getContextTokens()),
                formatTokens(agent.getMaxContextTokens()),
                agent.getContextUsagePercent());
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }
}
