package com.raph.agent;

import com.raph.llm.AbstractOpenaiClient;
import com.raph.llm.DeepSeekClient;
import com.raph.llm.LlmClient;
import com.raph.llm.LlmClient.*;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Agent {
    private final AbstractOpenaiClient client;
    private final ToolRegistry toolRegistry;
    private final List<Message> conversationHistory;
    private static final int MAX_ITERATIONS = 10;
    private int lastInputTokens = 0;
    private int lastOutputTokens = 0;

    public Agent(String apikey) {
        this.client = new DeepSeekClient(apikey);
        this.toolRegistry = new ToolRegistry();
        this.conversationHistory = new ArrayList<>();

        this.conversationHistory.add(Message.system(SYSTEM_PROMPT));
    }

    public String run(String userInput) throws IOException {
        // 添加用户输入
        conversationHistory.add(Message.user(userInput));

        lastInputTokens = 0;
        lastOutputTokens = 0;

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // 调用 LLM

            ChatResponse response = client.chat(
                    conversationHistory,
                    toolRegistry.getToolDefinitions()
            );

            lastInputTokens += response.inputTokens();
            lastOutputTokens += response.outputTokens();

            // 如果有工具调用
            if (response.hasToolCalls()) {
                // 记录助手消息
                conversationHistory.add(
                        Message.assistant(response.content(), response.toolCalls())
                );

                // 执行每个工具调用
                for (ToolCall toolCall : response.toolCalls()) {
                    String result = toolRegistry.executeTool(
                            toolCall.function().name(),
                            toolCall.function().arguments()
                    );

                    // 记录工具结果
                    conversationHistory.add(
                            Message.tool(toolCall.id(), result)
                    );
                }
                // 继续循环，让 LLM 根据结果继续思考
                continue;
            } else {
                // 没有工具调用，任务完成
                conversationHistory.add(
                        Message.assistant(response.content(), null)
                );
                return response.content();
            }
        }

        return "达到最大迭代次数限制";
    }

    private static final String SYSTEM_PROMPT = """
    你是一个智能编程助手，可以帮助用户完成各种任务。

    你可以使用以下工具来完成任务：
    1. read_file - 读取文件内容
    2. write_file - 写入文件内容
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
}
