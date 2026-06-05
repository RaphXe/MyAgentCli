package com.raph.agent;

import com.raph.llm.DeepSeekClient;
import com.raph.llm.LlmClient;
import com.raph.llm.LlmClient.*;
import com.raph.memory.MemoryManager;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Agent {
    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final List<Message> conversationHistory;
    private final MemoryManager memoryManager;
    private static final int MAX_ITERATIONS = 10;
    private int lastInputTokens = 0;
    private int lastOutputTokens = 0;
    private int currentContextTokens = 0;
    private final int maxContextTokens;

    public Agent(String apikey) {
        this(new DeepSeekClient(apikey), null);
    }

    public Agent(LlmClient client, MemoryManager memoryManager) {
        this(client, memoryManager, new ToolRegistry());
    }

    public Agent(LlmClient client, MemoryManager memoryManager, ToolRegistry toolRegistry) {
        this.client = client;
        this.toolRegistry = toolRegistry == null ? new ToolRegistry() : toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = memoryManager;
        this.maxContextTokens = loadMaxContextTokens();

        this.conversationHistory.add(Message.system(SYSTEM_PROMPT));
        this.currentContextTokens = estimateSystemPromptTokens();
    }

    public String run(String userInput) throws IOException {
        return run(userInput, LlmClient.StreamListener.NO_OP);
    }

    public String run(String userInput, LlmClient.StreamListener listener) throws IOException {
        LlmClient.StreamListener streamListener = listener == null ? LlmClient.StreamListener.NO_OP : listener;
        rebuildSystemMessage(userInput);

        conversationHistory.add(Message.user(userInput));

        lastInputTokens = 0;
        lastOutputTokens = 0;

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            if (memoryManager != null) {
                memoryManager.beforeChat(this);
            }

            ChatResponse response = client.chat(
                    conversationHistory,
                    toolRegistry.getToolDefinitions(),
                    streamListener
            );

            lastInputTokens += response.inputTokens();
            lastOutputTokens += response.outputTokens();
            currentContextTokens = response.inputTokens();

            if (memoryManager != null) {
                memoryManager.afterChat(this);
            }

            if (response.hasToolCalls()) {
                conversationHistory.add(
                        Message.assistant(response.content(), response.toolCalls())
                );

                for (ToolRegistry.ToolExecutionResult result : toolRegistry.executeTools(response.toolCalls())) {
                    conversationHistory.add(
                            Message.tool(result.toolCallId(), result.result())
                    );
                }
                continue;
            } else {
                conversationHistory.add(
                        Message.assistant(response.content(), null)
                );
                return response.content();
            }
        }

        return "达到最大迭代次数限制";
    }

    private void rebuildSystemMessage(String userInput) {
        if (memoryManager == null) return;

        String memoryContext = memoryManager.enrichSystemPrompt(userInput);
        String enriched = memoryContext != null && !memoryContext.isEmpty()
                ? SYSTEM_PROMPT + memoryContext
                : SYSTEM_PROMPT;

        if (!conversationHistory.isEmpty() && "system".equals(conversationHistory.get(0).role())) {
            conversationHistory.set(0, Message.system(enriched));
        }
    }

    private static final String SYSTEM_PROMPT = """
    你是一个智能编程助手，可以帮助用户完成各种任务。

    你可以使用以下工具来完成任务：
    1. read_file - 读取文件内容
    2. write_file - 写入文件内容，mode=overwrite 覆盖写入，mode=append 追加写入
    3. list_dir - 列出目录内容
    4. execute_command - 执行Shell命令
    5. create_project - 创建新项目结构

    当需要操作文件、执行命令或创建项目时，请使用工具调用。
    使用工具后，根据工具返回的结果继续思考下一步行动。

    请用中文回复用户。
    """;

    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(Message.system(SYSTEM_PROMPT));
        currentContextTokens = estimateSystemPromptTokens();
        lastInputTokens = 0;
        lastOutputTokens = 0;
    }

    public List<Message> getConversationHistory() {
        return conversationHistory;
    }

    public int getLastInputTokens() {
        return lastInputTokens;
    }

    public int getLastOutputTokens() {
        return lastOutputTokens;
    }

    public int getLastTotalTokens() {
        return lastInputTokens + lastOutputTokens;
    }

    public int getContextTokens() {
        return currentContextTokens;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public double getContextUsagePercent() {
        return maxContextTokens > 0 ? (double) currentContextTokens / maxContextTokens * 100.0 : 0.0;
    }

    private int estimateSystemPromptTokens() {
        return (int) Math.ceil(SYSTEM_PROMPT.length() / 3.5);
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
