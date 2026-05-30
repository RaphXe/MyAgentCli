package com.raph.agent;

import com.raph.llm.LlmClient;
import com.raph.plan.ExecutionPlan;
import com.raph.plan.Planner;
import com.raph.plan.Task;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.util.*;

public class PlanExecuteAgent {
    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final int outputTruncateLimit;

    private static final int MAX_TOOL_ITERATIONS = 8;

    public PlanExecuteAgent(LlmClient client, ToolRegistry toolRegistry, int outputTruncateLimit) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.planner = new Planner(client);
        this.outputTruncateLimit = outputTruncateLimit;
    }

    public String run(String userInput) {
        try {
            // ═══════════ Phase 1: 制定计划 ═══════════
            ExecutionPlan plan = planner.createPlan(userInput);
            plan.markStarted();

            StringBuilder output = new StringBuilder();
            output.append(formatPlan(plan));

            // ═══════════ Phase 2: 执行计划 ═══════════
            output.append("\n🚀 开始执行计划...\n");
            Map<String, String> taskResults = new LinkedHashMap<>();
            boolean allSuccess = true;

            for (String taskId : plan.getExecutionOrder()) {
                Task task = plan.getTask(taskId);
                output.append(String.format("\n⏳ [%s] %s\n", taskId, task.getDescription()));

                try {
                    task.markStarted();
                    String result = executeTask(task, taskResults, plan);
                    task.markCompleted(result);
                    taskResults.put(taskId, result);
                    output.append(String.format("   ✅ 完成\n"));
                } catch (Exception e) {
                    task.markFailed(e.getMessage());
                    output.append(String.format("   ❌ 失败: %s\n", e.getMessage()));
                    allSuccess = false;

                    // 尝试重新规划
                    ExecutionPlan newPlan = planner.replan(plan, e.getMessage());
                    output.append(formatPlan(newPlan));
                    // 递归执行新计划（简化处理：直接替换）
                    // 实际可更复杂，这里仅提示
                    output.append("🔄 建议重新运行以获取新的执行计划\n");
                    break;
                }
            }

            if (allSuccess) {
                plan.markCompleted();
                output.append("\n📊 所有任务执行完毕\n");

                // 输出最终结果汇总
                output.append("\n═══════════════════════════════════\n");
                output.append("📋 执行结果汇总:\n");
                for (Map.Entry<String, String> entry : taskResults.entrySet()) {
                    Task task = plan.getTask(entry.getKey());
                    output.append(String.format("\n── [%s] %s ──\n", entry.getKey(), task.getDescription()));
                    // 限制输出长度
                    String result = entry.getValue();
                    if (result.length() > outputTruncateLimit) {
                        result = result.substring(0, outputTruncateLimit)
                                + "\n...（输出已截断，限制: " + outputTruncateLimit + " 字符）";
                    }
                    output.append(result).append("\n");
                }
                output.append("═══════════════════════════════════\n");
            }

            return output.toString();
        } catch (Exception e) {
            return "❌ 计划执行失败: " + e.getMessage();
        }
    }

    /**
     * 执行单个任务：使用 LLM + 工具调用来完成
     */
    private String executeTask(Task task, Map<String, String> previousResults,
                               ExecutionPlan plan) throws IOException {
        // 构建上下文：前置任务的结果
        StringBuilder context = new StringBuilder();
        context.append("## 总体目标\n").append(plan.getGoal()).append("\n\n");
        context.append("## 当前任务\n").append(task.getDescription()).append("\n\n");

        if (!task.getDependencies().isEmpty()) {
            context.append("## 前置任务结果\n");
            for (String depId : task.getDependencies()) {
                String depResult = previousResults.get(depId);
                if (depResult != null) {
                    Task dep = plan.getTask(depId);
                    context.append(String.format("### [%s] %s\n", depId, dep.getDescription()));
                    context.append(depResult).append("\n\n");
                }
            }
        }

        // 构建消息列表
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(buildTaskSystemPrompt(task)));
        messages.add(LlmClient.Message.user(context.toString()));

        // 工具调用循环
        StringBuilder finalResult = new StringBuilder();
        int iteration = 0;

        while (iteration < MAX_TOOL_ITERATIONS) {
            iteration++;

            LlmClient.ChatResponse response = client.chat(
                    messages,
                    toolRegistry.getToolDefinitions()
            );

            if (response.hasToolCalls()) {
                // 记录助手消息（含工具调用）
                messages.add(LlmClient.Message.assistant(
                        response.getContent(), response.toolCalls()));

                // 执行每个工具调用
                for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                    String toolResult = toolRegistry.executeTool(
                            toolCall.function().name(),
                            toolCall.function().arguments()
                    );
                    messages.add(LlmClient.Message.tool(toolCall.id(), toolResult));
                }
                // 继续循环，让 LLM 根据工具结果继续
            } else {
                // 无工具调用，任务完成
                finalResult.append(response.getContent());
                break;
            }
        }

        if (finalResult.isEmpty()) {
            throw new IOException("达到最大工具调用轮次限制 (" + MAX_TOOL_ITERATIONS + ")");
        }

        return finalResult.toString();
    }

    /**
     * 根据任务类型构建系统提示词
     */
    private String buildTaskSystemPrompt(Task task) {
        String basePrompt = switch (task.getType()) {
            case FILE_READ -> """
                    你是一个文件读取助手。你的任务是读取指定文件的内容。
                    请使用 read_file 工具读取文件，然后简要总结文件内容。
                    不要做额外的深入分析，只返回文件的关键信息摘要。
                    """;
            case FILE_WRITE -> """
                    你是一个文件写入助手。你的任务是向文件写入内容。
                    请使用 write_file 工具完成写入操作。
                    写入完成后，简要说明写入的文件路径和内容概要。
                    """;
            case COMMAND -> """
                    你是一个命令执行助手。你的任务是执行Shell命令来完成指定操作。
                    请使用 execute_command 工具执行命令。
                    执行完成后，解释命令的输出结果。
                    """;
            case ANALYSIS -> """
                    你是一个分析专家。请根据前置任务的执行结果进行深入分析。
                    如果需要额外的信息，可以使用 read_file 或 execute_command 工具。
                    给出专业、具体的分析结论，列出发现的问题和建议。
                    请用中文回复。
                    """;
            case VERIFICATION -> """
                    你是一个验证专家。请验证前置任务的执行结果是否正确。
                    如果需要，可以使用 read_file 或 execute_command 工具来交叉验证。
                    给出明确的验证结论：通过 / 未通过，并说明原因。
                    请用中文回复。
                    """;
            default -> """
                    你是一个智能任务执行助手。请根据上下文完成任务。
                    如果需要，可以使用 read_file、write_file、execute_command 等工具。
                    完成后返回任务执行结果。
                    请用中文回复。
                    """;
        };

        return basePrompt + "\n请专注于当前任务，完成任务后返回结果，不要调用不必要的工具。";
    }

    /**
     * 格式化计划，便于展示
     */
    private String formatPlan(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n📋 执行计划\n");
        sb.append("═══════════════════════════════════\n");
        sb.append("目标: ").append(plan.getGoal()).append("\n");
        sb.append("摘要: ").append(plan.getSummary()).append("\n");
        sb.append("\n任务列表:\n");

        for (Task task : plan.getAllTasks()) {
            sb.append(String.format("  [%s] %s - %s",
                    task.getId(), task.getType(), task.getDescription()));
            if (!task.getDependencies().isEmpty()) {
                sb.append("  ← 依赖: ").append(String.join(", ", task.getDependencies()));
            }
            sb.append("\n");
        }

        sb.append("\n执行顺序: ");
        sb.append(String.join(" → ", plan.getExecutionOrder()));
        sb.append("\n═══════════════════════════════════\n");
        return sb.toString();
    }
}
