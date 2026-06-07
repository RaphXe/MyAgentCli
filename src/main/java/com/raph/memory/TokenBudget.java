package com.raph.memory;

public class TokenBudget {

    private final int maxContextTokens;

    private int currentConvTokens;
    private int compressedHistoryTokens;
    private int injectedMemoryTokens;

    public TokenBudget(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public synchronized void updateCurrentConvTokens(int tokens) {
        this.currentConvTokens = tokens;
    }

    public synchronized void updateCompressedHistoryTokens(int tokens) {
        this.compressedHistoryTokens = tokens;
    }

    public synchronized void updateInjectedMemoryTokens(int tokens) {
        this.injectedMemoryTokens = tokens;
    }

    public synchronized void reset() {
        this.currentConvTokens = 0;
        this.compressedHistoryTokens = 0;
        this.injectedMemoryTokens = 0;
    }

    public synchronized int total() {
        return currentConvTokens + compressedHistoryTokens + injectedMemoryTokens;
    }

    public synchronized boolean needsCompression() {
        return total() > (int) (maxContextTokens * 0.8);
    }

    public synchronized int getMaxContextTokens() {
        return maxContextTokens;
    }

    public synchronized int getCurrentConvTokens() {
        return currentConvTokens;
    }

    public synchronized int getCompressedHistoryTokens() {
        return compressedHistoryTokens;
    }

    public synchronized int getInjectedMemoryTokens() {
        return injectedMemoryTokens;
    }

    public synchronized double usagePercent() {
        return maxContextTokens > 0 ? (double) total() / maxContextTokens * 100.0 : 0.0;
    }
}
