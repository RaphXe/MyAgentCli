package com.raph.memory;

import com.raph.agent.Agent;
import com.raph.llm.LlmClient;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class MemoryManager {

    private final LongTermHistory longTermHistory;
    private final MemoryRetriever memoryRetriever;
    private final TokenBudget tokenBudget;
    private final ContextCompressor contextCompressor;
    private final LlmClient llmClient;

    private String lastInjectedContext;

    public MemoryManager(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.longTermHistory = new LongTermHistory();
        this.memoryRetriever = new MemoryRetriever();
        this.tokenBudget = new TokenBudget(loadMaxContextTokens());
        this.contextCompressor = new ContextCompressor();
    }

    public void init() {
        longTermHistory.load();
    }

    public String enrichSystemPrompt(String userQuery) {
        List<MemoryEntry> allMemories = longTermHistory.getAllEntries();
        if (allMemories.isEmpty()) {
            lastInjectedContext = "";
            return null;
        }

        List<MemoryRetriever.ScoredMemory> scored = memoryRetriever.retrieve(userQuery, allMemories, 5);
        if (scored.isEmpty()) {
            lastInjectedContext = "";
            return null;
        }

        StringBuilder sb = new StringBuilder("\n\n## 相关长期记忆\n");
        for (MemoryRetriever.ScoredMemory sm : scored) {
            sb.append("- ").append(sm.memory().content()).append("\n");
        }

        lastInjectedContext = sb.toString();
        tokenBudget.updateInjectedMemoryTokens(estimateTokens(lastInjectedContext));
        return lastInjectedContext;
    }

    public String getLastInjectedContext() {
        return lastInjectedContext;
    }

    public void beforeChat(Agent agent) {
        if (tokenBudget.needsCompression()) {
            List<LlmClient.Message> conversation = agent.getConversationHistory();
            try {
                ContextCompressor.CompressResult result = contextCompressor.compress(conversation, llmClient);
                if (result.performed()) {
                    int cutoff = result.removedCount();
                    while (conversation.size() > cutoff) {
                        conversation.remove(0);
                    }
                    conversation.add(0, result.summaryMessage());
                    tokenBudget.updateCompressedHistoryTokens(result.estimatedCompressedTokens());
                }
            } catch (IOException e) {
                System.err.println("上下文压缩失败: " + e.getMessage());
            }
        }
    }

    public void afterChat(Agent agent) {
        tokenBudget.updateCurrentConvTokens(agent.getContextTokens());
    }

    public void saveToMemory(String description, Agent agent) throws IOException {
        if (description == null || description.isBlank()) {
            System.out.println("描述为空，无法保存");
            return;
        }

        MemoryType type = description.contains("偏好") || description.contains("喜欢")
                || description.contains("习惯") ? MemoryType.PREFERENCE : MemoryType.FACT;

        MemoryEntry entry = new MemoryEntry(
                LongTermHistory.generateId(),
                description.trim(),
                type,
                Instant.now(),
                Map.of("source", "manual"),
                estimateTokens(description)
        );

        longTermHistory.addEntry(entry);
        longTermHistory.save();
        System.out.println("已保存 1 条记忆到长期记忆中");
    }

    public TokenBudget getTokenBudget() {
        return tokenBudget;
    }

    public LongTermHistory getLongTermHistory() {
        return longTermHistory;
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / 3.5);
    }

    private static int loadMaxContextTokens() {
        String value = System.getenv("CONTEXT_WINDOW_SIZE");
        if (value != null && !value.isBlank()) {
            try {
                int v = Integer.parseInt(value.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {}
        }
        return 1_048_576;
    }
}
