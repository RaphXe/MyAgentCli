package com.raph.agent;

import com.raph.llm.LlmClient;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 父 Agent 内部使用的短生命周期子 Agent。第一阶段只做受限只读辅助，不进入全局团队调度。
 */
public class SubAgentRunner {
    private static final int MAX_TOOL_ITERATIONS = 6;
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "project_tree", "search_files", "read_file", "list_dir", "execute_command"
    );
    private static final List<String> DEFAULT_TOOLS = List.of("project_tree", "search_files", "read_file");

    private final LlmClient client;
    private final ToolRegistry toolRegistry;

    public SubAgentRunner(LlmClient client, ToolRegistry toolRegistry) {
        this.client = client;
        this.toolRegistry = toolRegistry;
    }

    public Result run(Request request) throws IOException {
        Request safeRequest = request == null ? Request.empty() : request;
        List<String> allowedTools = allowedTools(safeRequest.allowedTools());

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(systemPrompt(safeRequest.role(), allowedTools)));
        messages.add(LlmClient.Message.user(userPrompt(safeRequest)));

        int iteration = 0;
        while (iteration++ < MAX_TOOL_ITERATIONS) {
            LlmClient.ChatResponse response = client.chat(
                    messages,
                    toolRegistry.getToolDefinitions(allowedTools),
                    LlmClient.StreamListener.NO_OP
            );
            if (response.hasToolCalls()) {
                messages.add(LlmClient.Message.assistant(response.content(), response.toolCalls()));
                for (ToolRegistry.ToolExecutionResult result : toolRegistry.executeTools(response.toolCalls(), allowedTools)) {
                    messages.add(LlmClient.Message.tool(result.toolCallId(), result.result()));
                }
                continue;
            }
            messages.add(LlmClient.Message.assistant(response.content(), null));
            return new Result(safeRequest.title(), safeRequest.role(), safeRequest.parentTaskId(),
                    response.content(), false);
        }

        return new Result(safeRequest.title(), safeRequest.role(), safeRequest.parentTaskId(),
                "子Agent达到工具调用轮数上限，未能完成报告。", true);
    }

    private List<String> allowedTools(List<String> requestedTools) {
        List<String> source = requestedTools == null || requestedTools.isEmpty() ? DEFAULT_TOOLS : requestedTools;
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (String tool : source) {
            if (tool == null) {
                continue;
            }
            String normalized = tool.trim();
            if (READ_ONLY_TOOLS.contains(normalized)) {
                allowed.add(normalized);
            }
        }
        if (allowed.isEmpty()) {
            allowed.addAll(DEFAULT_TOOLS);
        }
        return List.copyOf(allowed);
    }

    private String systemPrompt(String role, List<String> allowedTools) {
        String normalizedRole = role == null || role.isBlank() ? "Researcher" : role.trim();
        return """
                你是一个由父 Agent 临时创建的只读子 Agent。

                你的角色：%s
                允许工具：%s

                规则：
                1. 只服务于父任务，不要创建任务、委派任务、请求全局协作或给出最终用户答案。
                2. 不允许修改文件、创建项目、执行破坏性命令或改变 Git 状态。
                3. 工具预算很宝贵，最多只有 %d 轮工具调用。优先使用 project_tree/search_files 建立地图，再 read_file 读取少量关键文件。
                4. 一旦已经看到足以回答子任务的 2-5 个证据点，必须停止调用工具并输出报告；不要为了完整性递归扫描全项目。
                5. 若需要执行命令，只能用于只读检查，例如 pwd、ls、find、rg、mvn test 这类验证；不要安装依赖或写文件。
                6. 输出一份结构化中文报告，包含：结论、关键发现、证据/文件路径、风险、建议下一步。
                7. 如果信息不足，明确说明缺口，不要猜测，也不要继续无边界探索。
                """.formatted(normalizedRole, allowedTools, MAX_TOOL_ITERATIONS);
    }

    private String userPrompt(Request request) {
        StringBuilder sb = new StringBuilder();
        sb.append("父任务ID：").append(blankToDefault(request.parentTaskId(), "none")).append("\n");
        sb.append("子任务标题：").append(blankToDefault(request.title(), "未命名子任务")).append("\n\n");
        sb.append("子任务说明：\n").append(blankToDefault(request.prompt(), request.content())).append("\n\n");
        sb.append("请完成该子任务，并只返回报告正文。");
        return sb.toString();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public record Request(
            String title,
            String role,
            String prompt,
            String content,
            String parentTaskId,
            List<String> allowedTools
    ) {
        public static Request empty() {
            return new Request("未命名子任务", "Researcher", "", "", null, List.of());
        }
    }

    public record Result(
            String title,
            String role,
            String parentTaskId,
            String report,
            boolean incomplete
    ) {}
}
