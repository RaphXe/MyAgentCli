package com.raph.agent;

import com.raph.llm.LlmClient;
import com.raph.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeSubAgentIntegrationTest {

    @Test
    void spawnSubagentsReturnReportsToParentAgentInbox() throws Exception {
        FakeLlmClient client = new FakeLlmClient();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                client,
                new ToolRegistry(),
                5,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        String result = runtime.run("验证子Agent并发回投");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(result.contains("Multi-Agent 自治调度结束"));
        assertTrue(log.contains("spawn subagent batch parent=researcher children=2"), log);
        assertTrue(log.contains("child 阅读运行时"), log);
        assertTrue(log.contains("child 阅读任务板"), log);
        assertTrue(log.contains("子Agent批次结果"), log);
        assertTrue(log.contains("files=1"), log);
        assertTrue(log.contains("汇总完成"), log);
        assertTrue(log.contains("两个子Agent报告均已收到"), log);
    }

    @Test
    void coordinatorSpawnSubagentActionIsIgnored() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new CoordinatorSpawnLlmClient(),
                new ToolRegistry(),
                2,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("coordinator 不应直接启动子Agent");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("spawn_subagent ignored for coordinator"));
        assertFalse(log.contains("subagent -> coordinator"));
        assertTrue(log.contains("边界测试完成"));
    }

    @Test
    void optimizationReportTaskIsDelegatedToResearcherEvenWhenDescriptionMentionsVerification() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new OptimizationDelegationLlmClient(),
                new ToolRegistry(),
                1,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("创建优化建议任务");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("create task_1: 提出优化方向（重派） -> researcher"), log);
    }

    @Test
    void runtimeExecutesNavigationToolActions() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new NavigationToolActionLlmClient(),
                new ToolRegistry(),
                3,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("父agent直接调用 project_tree");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("tool project_tree -> researcher"), log);
        assertFalse(log.contains("unknown action: project_tree"), log);
    }

    @Test
    void creationTaskIsDelegatedToCoder() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new CreationDelegationLlmClient(),
                new ToolRegistry(),
                1,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("创建项目结构");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("create task_1: 创建项目结构和基础文件 -> coder"), log);
    }

    @Test
    void explorationTaskWithCreationContextIsDelegatedToResearcher() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new ExplorationDelegationLlmClient(),
                new ToolRegistry(),
                1,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("探索目录现状");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("create task_1: 探索/home/raph/example目录现状 -> researcher"), log);
    }

    @Test
    void batchCompletionUnlocksDependentTasksAndAllowsFinalAnswer() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new BatchCompletionLlmClient(),
                new ToolRegistry(),
                3,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("连续完成依赖任务");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("complete task_1 ok"), log);
        assertTrue(log.contains("complete task_2 ok"), log);
        assertTrue(log.contains("complete task_3 ok"), log);
        assertTrue(log.contains("批量任务已完成"), log);
    }

    @Test
    void reviewerCanReadFilesForReview() throws Exception {
        Path target = Path.of("/tmp/myagentcli-reviewer-read.txt");
        Files.writeString(target, "review me", StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new ReviewerReadFileLlmClient(target),
                new ToolRegistry(),
                3,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("reviewer 读取文件审查");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("tool read_file -> reviewer"), log);
        assertFalse(log.contains("tool read_file denied for reviewer"), log);
    }

    @Test
    void researcherWriteFileActionIsDenied() throws Exception {
        Path target = Path.of("/tmp/myagentcli-researcher-denied.txt");
        Files.deleteIfExists(target);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new ResearcherWriteDeniedLlmClient(target),
                new ToolRegistry(),
                2,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("researcher 不应写文件");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("tool write_file denied for researcher"), log);
        assertFalse(Files.exists(target));
    }

    @Test
    void coderWriteFileActionIsAllowed() throws Exception {
        Path target = Path.of("/tmp/myagentcli-coder-write.txt");
        Files.deleteIfExists(target);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new CoderWriteAllowedLlmClient(target),
                new ToolRegistry(),
                3,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("coder 可以写文件");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("tool write_file -> coder"), log);
        assertTrue(Files.exists(target), log);
        assertTrue(Files.readString(target).contains("written by coder"), log);
    }

    @Test
    void coderSubagentReturnsPatchPlanWithoutWriting() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new CoderSubagentPatchPlanLlmClient(),
                new ToolRegistry(),
                4,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("coder 使用子Agent生成修改方案");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("spawn subagent batch parent=coder children=1"), log);
        assertTrue(log.contains("proposed_changes=1"), log);
        assertTrue(log.contains("patch plan received"), log);
        assertFalse(log.contains("tool write_file -> subagent"), log);
    }

    @Test
    void coderCanUseWriteSubagentToModifyFile() throws Exception {
        Path target = Path.of("/tmp/myagentcli-write-subagent.txt");
        Files.deleteIfExists(target);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new CoderWriteSubagentLlmClient(target),
                new ToolRegistry(),
                4,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("coder 使用写子Agent辅助完成任务");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("spawn subagent batch parent=coder children=1"), log);
        assertTrue(log.contains("mode=write"), log);
        assertTrue(log.contains("writes=1"), log);
        assertTrue(Files.exists(target), log);
        assertTrue(Files.readString(target).contains("written by write subagent"), log);
        assertTrue(log.contains("write subagent finished"), log);
    }

    @Test
    void researcherCannotUseWriteSubagent() throws Exception {
        Path target = Path.of("/tmp/myagentcli-researcher-write-subagent-denied.txt");
        Files.deleteIfExists(target);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentRuntime runtime = new AgentRuntime(
                new ResearcherWriteSubagentDeniedLlmClient(target),
                new ToolRegistry(),
                3,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        runtime.run("researcher 不应启动写子Agent");
        String log = output.toString(StandardCharsets.UTF_8);

        assertTrue(log.contains("write mode denied for parent=researcher"), log);
        assertFalse(Files.exists(target), log);
    }

    private static final class FakeLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            Message system = messages.get(0);
            String lastUser = lastUser(messages);
            if (system.content().contains("受限子 Agent")) {
                return subAgentResponse(lastUser);
            }
            if (lastUser.contains("coordinator / Coordinator")) {
                return coordinatorResponse(lastUser);
            }
            if (lastUser.contains("researcher / Researcher")) {
                return researcherResponse(lastUser);
            }
            if (lastUser.contains("reviewer / Reviewer")) {
                return json("""
                        {"status":"done","actions":[{"type":"approve_task","task_id":"task_1","note":"两个子Agent报告均已收到，汇总产物可交付"}],"final_answer":null}
                        """);
            }
            return json("""
                    {"status":"idle","actions":[],"final_answer":null}
                    """);
        }

        private ChatResponse coordinatorResponse(String prompt) {
            if (prompt.contains("已通过审查")) {
                return json("""
                        {"status":"done","actions":[{"type":"final_answer","content":"汇总完成：两个子Agent报告均已收到"}],"final_answer":null}
                        """);
            }
            return json("""
                    {"status":"working","actions":[{"type":"create_task","title":"探索子Agent回投","description":"探索当前项目结构并验证子Agent结果是否能回投给父Agent","dependencies":[]}],"final_answer":null}
                    """);
        }

        private ChatResponse researcherResponse(String prompt) {
            if (prompt.contains("子Agent批次结果")) {
                return json("""
                        {"status":"done","actions":[{"type":"ready_for_review","task_id":"task_1","artifact":"汇总完成：两个子Agent报告均已收到"}],"final_answer":null}
                        """);
            }
            return json("""
                    {"status":"working","actions":[
                      {"type":"claim_task","task_id":"task_1"},
                      {"type":"start_task","task_id":"task_1"},
                      {"type":"spawn_subagents","parent_task_id":"task_1","children":[
                        {"role":"Researcher","title":"阅读运行时","prompt":"只读分析 AgentRuntime 的子Agent回投路径","allowed_tools":["read_file","list_dir"]},
                        {"role":"Researcher","title":"阅读任务板","prompt":"只读分析 TaskBoard 的任务状态流转","allowed_tools":["read_file","list_dir"]}
                      ]}
                    ],"final_answer":null}
                    """);
        }

        private ChatResponse subAgentResponse(String prompt) {
            if (prompt.contains("阅读运行时")) {
                return json("""
                        {"status":"complete","summary":"AgentRuntime 可以将子Agent结果批量投递回父Agent inbox。","findings":["批次结果使用 ANSWER 回投","父Agent负责聚合"],"files_read":["src/main/java/com/raph/agent/AgentRuntime.java"],"risks":[],"recommendations":["保留结构化摘要"],"proposed_changes":[],"confidence":0.9}
                        """);
            }
            if (prompt.contains("阅读任务板")) {
                return json("""
                        {"status":"complete","summary":"TaskBoard 支持父任务从 IN_PROGRESS 进入 READY_FOR_REVIEW。","findings":["READY_FOR_REVIEW 后由 Reviewer approve","APPROVED 可作为终态"],"files_read":["src/main/java/com/raph/agent/TaskBoard.java"],"risks":[],"recommendations":["继续保持状态机单向流转"],"proposed_changes":[],"confidence":0.8}
                        """);
            }
            return json("""
                    {"status":"complete","summary":"子Agent完成。","findings":[],"files_read":[],"risks":[],"recommendations":[],"proposed_changes":[],"confidence":0.5}
                    """);
        }

        private static String lastUser(List<Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message message = messages.get(i);
                if ("user".equals(message.role())) {
                    return message.content();
                }
            }
            return "";
        }

        private static ChatResponse json(String content) {
            return new ChatResponse("assistant", content, null, 1, 1);
        }
    }

    private static final class CoordinatorSpawnLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                return new ChatResponse("assistant", """
                        {"status":"done","actions":[
                          {"type":"spawn_subagent","role":"Researcher","title":"不应执行","prompt":"如果执行就说明边界失败","allowed_tools":["project_tree"]},
                          {"type":"final_answer","content":"边界测试完成"}
                        ],"final_answer":null}
                        """, null, 1, 1);
            }
            return new ChatResponse("assistant", """
                    {"status":"idle","actions":[],"final_answer":null}
                    """, null, 1, 1);
        }
    }

    private static final class OptimizationDelegationLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                return new ChatResponse("assistant", """
                        {"status":"working","actions":[
                          {"type":"create_task","title":"提出优化方向（重派）","description":"验证已批准的组织架构分析，并提出优化建议报告","dependencies":[]}
                        ],"final_answer":null}
                        """, null, 1, 1);
            }
            return new ChatResponse("assistant", """
                    {"status":"idle","actions":[],"final_answer":null}
                    """, null, 1, 1);
        }
    }

    private static final class NavigationToolActionLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                return new ChatResponse("assistant", """
                        {"status":"working","actions":[
                          {"type":"create_task","title":"探索项目结构","description":"探索项目结构","dependencies":[]}
                        ],"final_answer":null}
                        """, null, 1, 1);
            }
            if (lastUser.contains("researcher / Researcher")) {
                if (lastUser.contains("工具结果") || lastUser.contains("项目树")) {
                    return new ChatResponse("assistant", """
                            {"status":"done","actions":[{"type":"complete_task","task_id":"task_1"}],"final_answer":null}
                            """, null, 1, 1);
                }
                return new ChatResponse("assistant", """
                        {"status":"working","actions":[
                          {"type":"claim_task","task_id":"task_1"},
                          {"type":"start_task","task_id":"task_1"},
                          {"type":"project_tree","path":"src","max_depth":"1","include_files":"true"}
                        ],"final_answer":null}
                        """, null, 1, 1);
            }
            return new ChatResponse("assistant", """
                    {"status":"idle","actions":[],"final_answer":null}
                    """, null, 1, 1);
        }
    }

    private static final class ResearcherWriteDeniedLlmClient implements LlmClient {
        private final Path target;

        private ResearcherWriteDeniedLlmClient(Path target) {
            this.target = target;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"create_task","title":"探索并误写文件","description":"探索项目结构，不应写文件","dependencies":[]}
                        ],"final_answer":null}
                        """);
            }
            if (lastUser.contains("researcher / Researcher")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"claim_task","task_id":"task_1"},
                          {"type":"start_task","task_id":"task_1"},
                          {"type":"write_file","path":"%s","content":"should not be written","mode":"overwrite"}
                        ],"final_answer":null}
                        """.formatted(target));
            }
            return idle();
        }
    }

    private static final class CreationDelegationLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"create_task","title":"创建项目结构和基础文件","description":"在 /tmp/example 下创建目录结构、主入口文件和 README","dependencies":[]}
                        ],"final_answer":null}
                        """);
            }
            return idle();
        }
    }

    private static final class ExplorationDelegationLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"create_task","title":"探索/home/raph/example目录现状","description":"查看该目录是否存在、是否有已有文件，以确定是否需要创建新项目。只读操作。","dependencies":[]}
                        ],"final_answer":null}
                        """);
            }
            return idle();
        }
    }

    private static final class BatchCompletionLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                if (lastUser.contains("[DONE]")) {
                    return response("""
                            {"status":"done","actions":[{"type":"final_answer","content":"批量任务已完成"}],"final_answer":null}
                            """);
                }
                return response("""
                        {"status":"working","actions":[
                          {"type":"create_task","title":"创建结构","description":"创建项目结构","dependencies":[]},
                          {"type":"create_task","title":"实现代码","description":"实现源码文件","dependencies":["task_1"]},
                          {"type":"create_task","title":"编写演示","description":"编写主类演示","dependencies":["task_2"]}
                        ],"final_answer":null}
                        """);
            }
            if (lastUser.contains("coder / Coder")) {
                return response("""
                        {"status":"done","actions":[
                          {"type":"complete_task","task_id":"task_1"},
                          {"type":"complete_task","task_id":"task_2"},
                          {"type":"complete_task","task_id":"task_3"}
                        ],"final_answer":null}
                        """);
            }
            return idle();
        }
    }

    private static final class ReviewerReadFileLlmClient implements LlmClient {
        private final Path target;

        private ReviewerReadFileLlmClient(Path target) {
            this.target = target;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"create_task","title":"审查文件","description":"审查文件内容","dependencies":[]}
                        ],"final_answer":null}
                        """);
            }
            if (lastUser.contains("researcher / Researcher")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"ready_for_review","task_id":"task_1","artifact":"请审查测试文件"}
                        ],"final_answer":null}
                        """);
            }
            if (lastUser.contains("reviewer / Reviewer")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"read_file","path":"%s"},
                          {"type":"approve_task","task_id":"task_1","note":"读取成功并通过"}
                        ],"final_answer":null}
                        """.formatted(target));
            }
            return idle();
        }
    }

    private static final class CoderWriteAllowedLlmClient implements LlmClient {
        private final Path target;

        private CoderWriteAllowedLlmClient(Path target) {
            this.target = target;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"create_task","title":"实现写文件功能","description":"实现代码修改，写入测试文件","dependencies":[]}
                        ],"final_answer":null}
                        """);
            }
            if (lastUser.contains("coder / Coder")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"claim_task","task_id":"task_1"},
                          {"type":"start_task","task_id":"task_1"},
                          {"type":"write_file","path":"%s","content":"written by coder","mode":"overwrite"}
                        ],"final_answer":null}
                        """.formatted(target));
            }
            return idle();
        }
    }

    private static final class CoderSubagentPatchPlanLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            Message system = messages.get(0);
            String lastUser = FakeLlmClient.lastUser(messages);
            if (system.content().contains("受限子 Agent")) {
                return response("""
                        {"status":"complete","summary":"生成一个修改方案。","findings":["目标文件需要小改"],"files_read":["src/main/java/com/raph/agent/TeamAgent.java"],"risks":["需由父Coder执行写入"],"recommendations":["由父Coder统一落盘"],"proposed_changes":[{"file":"src/main/java/com/raph/agent/TeamAgent.java","intent":"补充提示词","patch_hint":"追加 Coder 写文件边界说明"}],"confidence":0.8}
                        """);
            }
            if (lastUser.contains("coordinator / Coordinator")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"create_task","title":"实现提示词修改","description":"修改代码并验证","dependencies":[]}
                        ],"final_answer":null}
                        """);
            }
            if (lastUser.contains("coder / Coder")) {
                if (lastUser.contains("proposed_changes")) {
                    return response("""
                            {"status":"done","actions":[{"type":"send_message","to":"coordinator","message_type":"REPORT_PROGRESS","content":"patch plan received"}],"final_answer":null}
                            """);
                }
                return response("""
                        {"status":"working","actions":[
                          {"type":"claim_task","task_id":"task_1"},
                          {"type":"start_task","task_id":"task_1"},
                          {"type":"spawn_subagents","parent_task_id":"task_1","children":[
                            {"role":"Coder","title":"生成提示词修改方案","prompt":"阅读 TeamAgent 并提出 patch plan","allowed_tools":["read_file"]}
                          ]}
                        ],"final_answer":null}
                        """);
            }
            return idle();
        }
    }

    private static final class CoderWriteSubagentLlmClient implements LlmClient {
        private final Path target;

        private CoderWriteSubagentLlmClient(Path target) {
            this.target = target;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            Message system = messages.get(0);
            String lastUser = FakeLlmClient.lastUser(messages);
            if (system.content().contains("受限子 Agent")) {
                if (messages.stream().anyMatch(message -> "tool".equals(message.role()))) {
                    return response("""
                            {"status":"complete","summary":"写子Agent已完成文件写入。","findings":["目标文件已写入"],"files_read":[],"files_written":["%s"],"risks":[],"recommendations":["父Coder继续验证并提交审查"],"proposed_changes":[],"confidence":0.85}
                            """.formatted(target));
                }
                return new ChatResponse("assistant", "", List.of(new ToolCall(
                        "write-1",
                        new ToolCall.Function("write_file", """
                                {"path":"%s","content":"written by write subagent","mode":"overwrite"}
                                """.formatted(target))
                )), 1, 1);
            }
            if (lastUser.contains("coordinator / Coordinator")) {
                if (lastUser.contains("write subagent finished")) {
                    return response("""
                            {"status":"done","actions":[{"type":"final_answer","content":"写子Agent测试完成"}],"final_answer":null}
                            """);
                }
                return response("""
                        {"status":"working","actions":[
                          {"type":"create_task","title":"实现写子Agent文件修改","description":"使用写子Agent创建测试文件","dependencies":[]}
                        ],"final_answer":null}
                        """);
            }
            if (lastUser.contains("coder / Coder")) {
                if (lastUser.contains("files_written")) {
                    return response("""
                            {"status":"done","actions":[{"type":"send_message","to":"coordinator","message_type":"REPORT_PROGRESS","content":"write subagent finished"}],"final_answer":null}
                            """);
                }
                return response("""
                        {"status":"working","actions":[
                          {"type":"claim_task","task_id":"task_1"},
                          {"type":"start_task","task_id":"task_1"},
                          {"type":"spawn_subagents","parent_task_id":"task_1","children":[
                            {"role":"Coder","mode":"write","title":"写入目标测试文件","prompt":"写入指定测试文件","allowed_tools":["write_file"]}
                          ]}
                        ],"final_answer":null}
                        """);
            }
            return idle();
        }
    }

    private static final class ResearcherWriteSubagentDeniedLlmClient implements LlmClient {
        private final Path target;

        private ResearcherWriteSubagentDeniedLlmClient(Path target) {
            this.target = target;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            String lastUser = FakeLlmClient.lastUser(messages);
            if (lastUser.contains("coordinator / Coordinator")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"create_task","title":"探索并尝试写子Agent","description":"探索项目结构，不应写文件","dependencies":[]}
                        ],"final_answer":null}
                        """);
            }
            if (lastUser.contains("researcher / Researcher")) {
                return response("""
                        {"status":"working","actions":[
                          {"type":"claim_task","task_id":"task_1"},
                          {"type":"start_task","task_id":"task_1"},
                          {"type":"spawn_subagent","role":"Coder","mode":"write","title":"不应写入","prompt":"尝试写入 %s","parent_task_id":"task_1","allowed_tools":["write_file"]}
                        ],"final_answer":null}
                        """.formatted(target));
            }
            return idle();
        }
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 1, 1);
    }

    private static LlmClient.ChatResponse idle() {
        return response("""
                {"status":"idle","actions":[],"final_answer":null}
                """);
    }
}
