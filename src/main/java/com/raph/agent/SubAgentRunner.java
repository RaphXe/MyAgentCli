package com.raph.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raph.llm.LlmClient;
import com.raph.skill.SkillPrompts;
import com.raph.skill.ToolSkillResolver;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 父 Agent 内部使用的短生命周期子 Agent。它受限、不进入全局团队调度。
 */
public class SubAgentRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOOL_ITERATIONS = 7;
    private static final int MAX_REPEATED_TOOL_SIGNATURES = 2;
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "project_tree", "search_files", "read_file", "list_dir"
    );
    private static final Set<String> WRITE_TOOLS = Set.of(
            "project_tree", "search_files", "read_file", "list_dir", "write_file"
    );
    private static final List<String> DEFAULT_TOOLS = List.of("project_tree", "search_files", "read_file");
    private static final List<String> DEFAULT_WRITE_TOOLS = List.of("project_tree", "search_files", "read_file", "write_file");

    private final LlmClient client;
    private final ToolRegistry toolRegistry;

    public SubAgentRunner(LlmClient client, ToolRegistry toolRegistry) {
        this.client = client;
        this.toolRegistry = toolRegistry;
    }

    public Result run(Request request) throws IOException {
        return run(request, Observer.NO_OP);
    }

    public Result run(Request request, Observer observer) throws IOException {
        Observer safeObserver = observer == null ? Observer.NO_OP : observer;
        Request safeRequest = request == null ? Request.empty() : request;
        String mode = normalizeMode(safeRequest.mode());
        List<String> allowedTools = allowedTools(safeRequest.allowedTools(), mode);
        RunStats stats = new RunStats();
        Set<Integer> injectedToolSkillPrompts = new LinkedHashSet<>();

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(systemPrompt(safeRequest.role(), mode, allowedTools)));
        messages.add(LlmClient.Message.user(userPrompt(safeRequest)));

        int iteration = 0;
        while (iteration++ < MAX_TOOL_ITERATIONS) {
            LlmClient.ChatResponse response = client.chat(
                    messages,
                    toolRegistry.getToolDefinitions(allowedTools),
                    LlmClient.StreamListener.NO_OP
            );
            if (response.hasToolCalls()) {
                String toolSkillPrompt = ToolSkillResolver.defaults().renderToolCallUsage(response.toolCalls());
                if (injectToolSkillPrompt(messages, injectedToolSkillPrompts, toolSkillPrompt)) {
                    continue;
                }
                stats.recordToolIteration(response.toolCalls());
                safeObserver.onEvent("tool-iteration#" + stats.toolIterations()
                        + " calls=" + stats.latestToolCalls());
                if (stats.hasRepeatedToolSignature()) {
                    safeObserver.onEvent("stop repeated-tool-signature calls=" + stats.latestToolCalls());
                    Report report = Report.incomplete(
                            "工具调用出现重复，已提前停止，避免无边界探索。",
                            stats
                    );
                    return new Result(safeRequest.title(), safeRequest.role(), safeRequest.parentTaskId(),
                            report, true, stats);
                }
                messages.add(LlmClient.Message.assistant(response.content(), response.toolCalls()));
                for (ToolRegistry.ToolExecutionResult result : toolRegistry.executeTools(response.toolCalls(), allowedTools)) {
                    messages.add(LlmClient.Message.tool(result.toolCallId(), result.result()));
                }
                continue;
            }
            messages.add(LlmClient.Message.assistant(response.content(), null));
            Report report = Report.parse(response.content(), stats);
            return new Result(safeRequest.title(), safeRequest.role(), safeRequest.parentTaskId(),
                    report, false, stats);
        }

        Report report = Report.incomplete("子Agent达到工具调用轮数上限，未能完成完整报告。", stats);
        return new Result(safeRequest.title(), safeRequest.role(), safeRequest.parentTaskId(),
                report, true, stats);
    }

    private List<String> allowedTools(List<String> requestedTools, String mode) {
        boolean writeMode = "write".equals(mode);
        List<String> defaultTools = writeMode ? DEFAULT_WRITE_TOOLS : DEFAULT_TOOLS;
        Set<String> toolPolicy = writeMode ? WRITE_TOOLS : READ_ONLY_TOOLS;
        List<String> source = requestedTools == null || requestedTools.isEmpty() ? defaultTools : requestedTools;
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (String tool : source) {
            if (tool == null) {
                continue;
            }
            String normalized = tool.trim();
            if (toolPolicy.contains(normalized)) {
                allowed.add(normalized);
            }
        }
        if (allowed.isEmpty()) {
            allowed.addAll(defaultTools);
        }
        return List.copyOf(allowed);
    }

    private String systemPrompt(String role, String mode, List<String> allowedTools) {
        String normalizedRole = role == null || role.isBlank() ? "Researcher" : role.trim();
        String modeRules = "write".equals(mode) ? """
                写子Agent额外规则：
                A. 你是 Coder 的写子Agent，可以在父任务边界内使用 write_file 修改文件；不要创建任务、不要审批、不要输出最终用户答案。
                B. 只能修改子任务明确要求的文件或为了完成该子任务必须创建的少量文件；不要改 Git 状态，不要删除文件，不要执行命令。
                C. 写入前先用只读工具确认目标路径和上下文；如果目标不明确，停止并报告缺口，不要猜测写入。
                D. 写入后必须在 JSON 中报告 files_written，并说明验证状态；无法验证时写明原因，交给父 Coder 继续验证。
                """ : """
                读子Agent额外规则：
                A. 你只能进行只读探索、代码阅读、方案比较、验证或 patch plan 建议。
                B. 不允许修改文件、创建项目、执行命令、改变 Git 状态或绕过父 Agent 权限。
                C. 如果你的角色是 Coder 子Agent，只能输出 proposed_changes 形式的 patch plan：file、intent、patch_hint、risks。不要调用或建议直接执行 write_file。
                """;
        return SkillPrompts.addendum("core/subagent", """
                你是一个由父 Agent 临时创建的受限子 Agent。

                你的角色：%s
                子Agent模式：%s
                允许工具：%s

                规则：
                1. 只服务于父任务，不要创建任务、委派任务、请求全局协作或给出最终用户答案。
                2. 工具预算很宝贵，最多只有 %d 轮工具调用。你必须根据任务目标动态规划工具使用，而不是机械地扫描目录或读取尽可能多的文件。
                3. 第一次获得项目/目录地图后（例如 project_tree、search_files 或 list_dir 的结果），先判断当前证据是否已经足以回答子任务：如果足够，立即停止工具调用并输出报告；如果不足，只选择信息增益最高的下一步工具。
                4. 每一轮工具调用前都要隐式评估“这次调用能补充什么关键证据”。不要为了完整性、放心或递归穷尽而继续探索；不要逐层展开已经能由项目树概括的目录。
                5. read_file 只读取能显著改变结论的关键文件。目录结构类任务优先总结模块地图；架构/优化类任务优先读取入口、调度、状态机或配置等少量代表性文件。
                6. 不允许执行命令；如需验证命令，由父 Coder 或 Tester 负责。
                %s
                8. 必须只输出 JSON 对象，不要输出 Markdown。格式：
                   {"status":"complete|incomplete","summary":"...","findings":["..."],"files_read":["..."],"files_written":["..."],"risks":["..."],"recommendations":["..."],"proposed_changes":[{"file":"...","intent":"...","patch_hint":"..."}],"confidence":0.0}
                9. 如果信息不足，明确说明缺口和建议父Agent如何补充，不要猜测，也不要继续无边界探索。
                """.formatted(normalizedRole, mode, allowedTools, MAX_TOOL_ITERATIONS, modeRules));
    }

    private boolean injectToolSkillPrompt(List<LlmClient.Message> messages, Set<Integer> injected, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        int key = prompt.hashCode();
        if (!injected.add(key)) {
            return false;
        }
        messages.add(LlmClient.Message.user(prompt));
        return true;
    }

    private String userPrompt(Request request) {
        StringBuilder sb = new StringBuilder();
        sb.append("父任务ID：").append(blankToDefault(request.parentTaskId(), "none")).append("\n");
        sb.append("子任务标题：").append(blankToDefault(request.title(), "未命名子任务")).append("\n\n");
        sb.append("子任务说明：\n").append(blankToDefault(request.prompt(), request.content())).append("\n\n");
        sb.append("请完成该子任务，并只返回 JSON 报告对象。");
        return sb.toString();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public record Request(
            String title,
            String role,
            String mode,
            String prompt,
            String content,
            String parentTaskId,
            List<String> allowedTools
    ) {
        public static Request empty() {
            return new Request("未命名子任务", "Researcher", "read", "", "", null, List.of());
        }
    }

    public record Result(
            String title,
            String role,
            String parentTaskId,
            Report report,
            boolean incomplete,
            RunStats stats
    ) {}

    public interface Observer {
        Observer NO_OP = event -> {};

        void onEvent(String event);
    }

    public record Report(
            String status,
            String summary,
            List<String> findings,
            List<String> filesRead,
            List<String> filesWritten,
            List<String> risks,
            List<Recommendation> recommendations,
            List<ProposedChange> proposedChanges,
            double confidence,
            String raw
    ) {
        public static Report parse(String raw, RunStats stats) {
            if (raw == null || raw.isBlank()) {
                return incomplete("子Agent未返回内容。", stats);
            }
            String cleaned = extractJsonObject(stripCodeFence(raw.trim()));
            try {
                JsonNode root = MAPPER.readTree(cleaned);
                return new Report(
                        text(root, "status", "complete"),
                        text(root, "summary", raw),
                        stringList(root.path("findings")),
                        mergeFiles(stringList(root.path("files_read")), stats.filesRead()),
                        mergeFiles(stringList(root.path("files_written")), stats.filesWritten()),
                        stringList(root.path("risks")),
                        parseRecommendations(root.path("recommendations")),
                        parseProposedChanges(root.path("proposed_changes")),
                        root.path("confidence").isNumber() ? root.path("confidence").asDouble() : 0.6,
                        raw
                );
            } catch (Exception ignored) {
                return new Report(
                        "complete",
                        raw.trim(),
                        List.of(raw.trim()),
                        List.copyOf(stats.filesRead()),
                        List.copyOf(stats.filesWritten()),
                        List.of(),
                        List.of(),
                        List.of(),
                        0.5,
                        raw
                );
            }
        }

        public static Report incomplete(String reason, RunStats stats) {
            return new Report(
                    "incomplete",
                    reason,
                    List.of(reason),
                    stats == null ? List.of() : List.copyOf(stats.filesRead()),
                    stats == null ? List.of() : List.copyOf(stats.filesWritten()),
                    List.of(reason),
                    List.of(),
                    List.of(),
                    0.2,
                    reason
            );
        }

        public String compact() {
            StringBuilder sb = new StringBuilder();
            sb.append("status=").append(status).append("\n");
            sb.append("summary=").append(summary).append("\n");
            if (!findings.isEmpty()) sb.append("findings=").append(findings).append("\n");
            if (!filesRead.isEmpty()) sb.append("files_read=").append(filesRead).append("\n");
            if (!filesWritten.isEmpty()) sb.append("files_written=").append(filesWritten).append("\n");
            if (!risks.isEmpty()) sb.append("risks=").append(risks).append("\n");
            if (!recommendations.isEmpty()) sb.append("recommendations=").append(recommendations).append("\n");
            if (!proposedChanges.isEmpty()) sb.append("proposed_changes=").append(proposedChanges).append("\n");
            sb.append("confidence=").append(confidence);
            return sb.toString();
        }
    }

    public record Recommendation(String text) {}

    public record ProposedChange(String file, String intent, String patchHint) {}

    public static final class RunStats {
        private final Map<String, Integer> toolSignatures = new LinkedHashMap<>();
        private final List<String> toolCalls = new ArrayList<>();
        private List<String> latestToolCalls = List.of();
        private final LinkedHashSet<String> filesRead = new LinkedHashSet<>();
        private final LinkedHashSet<String> filesWritten = new LinkedHashSet<>();
        private int toolIterations;

        private void recordToolIteration(List<LlmClient.ToolCall> calls) {
            toolIterations++;
            List<String> latest = new ArrayList<>();
            if (calls == null) {
                latestToolCalls = List.of();
                return;
            }
            for (LlmClient.ToolCall call : calls) {
                String signature = signature(call);
                toolSignatures.merge(signature, 1, Integer::sum);
                toolCalls.add(signature);
                latest.add(signature);
                String file = readFilePath(call);
                if (file != null) filesRead.add(file);
                String written = writeFilePath(call);
                if (written != null) filesWritten.add(written);
            }
            latestToolCalls = List.copyOf(latest);
        }

        private boolean hasRepeatedToolSignature() {
            return toolSignatures.values().stream().anyMatch(count -> count > MAX_REPEATED_TOOL_SIGNATURES);
        }

        public int toolIterations() {
            return toolIterations;
        }

        public List<String> toolCalls() {
            return List.copyOf(toolCalls);
        }

        public List<String> latestToolCalls() {
            return List.copyOf(latestToolCalls);
        }

        public List<String> filesRead() {
            return List.copyOf(filesRead);
        }

        public List<String> filesWritten() {
            return List.copyOf(filesWritten);
        }

        private static String signature(LlmClient.ToolCall call) {
            if (call == null || call.function() == null) return "unknown:{}";
            String args = call.function().arguments() == null ? "{}" : call.function().arguments();
            return call.function().name() + ":" + normalizeArgs(args);
        }

        private static String readFilePath(LlmClient.ToolCall call) {
            if (call == null || call.function() == null || !"read_file".equals(call.function().name())) {
                return null;
            }
            try {
                JsonNode root = MAPPER.readTree(call.function().arguments());
                JsonNode path = root.path("path");
                return path.isTextual() ? path.asText() : null;
            } catch (Exception ignored) {
                return null;
            }
        }

        private static String writeFilePath(LlmClient.ToolCall call) {
            if (call == null || call.function() == null || !"write_file".equals(call.function().name())) {
                return null;
            }
            try {
                JsonNode root = MAPPER.readTree(call.function().arguments());
                JsonNode path = root.path("path");
                return path.isTextual() ? path.asText() : null;
            } catch (Exception ignored) {
                return null;
            }
        }

        private static String normalizeArgs(String args) {
            if (args == null) return "{}";
            String trimmed = args.replaceAll("\\s+", "");
            return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
        }
    }

    private static List<String> mergeFiles(List<String> reported, List<String> observed) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (reported != null) values.addAll(reported);
        if (observed != null) values.addAll(observed);
        return List.copyOf(values);
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "read";
        }
        String normalized = mode.trim().toLowerCase();
        if ("write".equals(normalized) || "writer".equals(normalized) || "mutation".equals(normalized)) {
            return "write";
        }
        return "read";
    }

    private static List<Recommendation> parseRecommendations(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Recommendation> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) continue;
            if (item.isTextual()) values.add(new Recommendation(item.asText()));
            else values.add(new Recommendation(item.toString()));
        }
        return List.copyOf(values);
    }

    private static List<ProposedChange> parseProposedChanges(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<ProposedChange> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) continue;
            values.add(new ProposedChange(
                    text(item, "file", ""),
                    text(item, "intent", ""),
                    text(item, "patch_hint", text(item, "patchHint", ""))
            ));
        }
        return List.copyOf(values);
    }

    private static String extractJsonObject(String value) {
        if (value == null) return "";
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) return value.substring(start, end + 1).trim();
        return value.trim();
    }

    private static String stripCodeFence(String value) {
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "");
            value = value.replaceFirst("\\s*```$", "");
        }
        return value.trim();
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asText();
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isNull()) values.add(item.asText());
        }
        return List.copyOf(values);
    }
}
