package com.raph.memory;

import com.raph.llm.LlmClient;

import java.io.IOException;
import java.util.List;

public class ContextCompressor {

    private static final String COMPRESSION_SYSTEM_PROMPT = """
            你是一个对话摘要专家。请将以下对话历史压缩为简洁的中文摘要。
            摘要应保留：
            1. 用户的核心目标和需求
            2. 已完成的关键操作和结果
            3. 重要的决策和发现
            4. 未完成或待处理的事项
            请用200字以内的中文概括。只输出摘要内容，不要加任何前缀说明。
            """;

    public CompressResult compress(List<LlmClient.Message> conversation, LlmClient client) throws IOException {
        int recentCount = countRecentTurns(conversation);
        if (recentCount >= conversation.size()) {
            return CompressResult.noOp();
        }

        int cutoff = conversation.size() - recentCount;
        List<LlmClient.Message> oldMessages = conversation.subList(0, cutoff);
        String transcript = buildTranscript(oldMessages);

        List<LlmClient.Message> llmMessages = List.of(
                LlmClient.Message.system(COMPRESSION_SYSTEM_PROMPT),
                LlmClient.Message.user(transcript)
        );

        LlmClient.ChatResponse response = client.chat(llmMessages, null);
        String summary = response.getContent();

        LlmClient.Message summaryMsg = LlmClient.Message.system("历史对话摘要:\n" + summary);

        int estimatedTokens = (int) Math.ceil(summary.length() / 3.5);
        int removedTokens = estimateMessageTokens(oldMessages);

        return new CompressResult(summaryMsg, estimatedTokens, removedTokens, cutoff);
    }

    private int countRecentTurns(List<LlmClient.Message> conversation) {
        int userCount = 0;
        for (int i = conversation.size() - 1; i >= 0; i--) {
            if ("user".equals(conversation.get(i).role())) {
                userCount++;
                if (userCount >= 3) {
                    return conversation.size() - i;
                }
            }
        }
        return conversation.size();
    }

    private String buildTranscript(List<LlmClient.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message msg : messages) {
            String roleLabel = switch (msg.role()) {
                case "user" -> "用户";
                case "assistant" -> "助手";
                case "tool" -> "工具结果";
                default -> msg.role();
            };
            String content = msg.content();
            if (content != null && !content.isBlank()) {
                if (content.length() > 2000) {
                    content = content.substring(0, 2000) + "...";
                }
                sb.append(roleLabel).append(": ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    private int estimateMessageTokens(List<LlmClient.Message> messages) {
        int total = 0;
        for (LlmClient.Message msg : messages) {
            String content = msg.content();
            if (content != null) {
                total += (int) Math.ceil(content.length() / 3.5);
            }
        }
        return total;
    }

    public record CompressResult(
            LlmClient.Message summaryMessage,
            int estimatedCompressedTokens,
            int removedTokens,
            int removedCount
    ) {
        public static CompressResult noOp() {
            return new CompressResult(null, 0, 0, 0);
        }

        public boolean performed() {
            return summaryMessage != null;
        }
    }
}
