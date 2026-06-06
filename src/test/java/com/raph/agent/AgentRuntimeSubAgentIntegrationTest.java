package com.raph.agent;

import com.raph.llm.LlmClient;
import com.raph.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        assertTrue(log.contains("spawn subagent 阅读运行时"), log);
        assertTrue(log.contains("spawn subagent 阅读任务板"), log);
        assertTrue(log.contains("subagent -> researcher"), log);
        assertTrue(log.contains("子Agent报告"), log);
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

    private static final class FakeLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            Message system = messages.get(0);
            String lastUser = lastUser(messages);
            if (system.content().contains("只读子 Agent")) {
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
            if (prompt.contains("子Agent报告")) {
                return json("""
                        {"status":"done","actions":[{"type":"ready_for_review","task_id":"task_1","artifact":"汇总完成：两个子Agent报告均已收到"}],"final_answer":null}
                        """);
            }
            return json("""
                    {"status":"working","actions":[
                      {"type":"claim_task","task_id":"task_1"},
                      {"type":"start_task","task_id":"task_1"},
                      {"type":"spawn_subagent","role":"Researcher","title":"阅读运行时","prompt":"只读分析 AgentRuntime 的子Agent回投路径","parent_task_id":"task_1","allowed_tools":["read_file","list_dir"]},
                      {"type":"spawn_subagent","role":"Researcher","title":"阅读任务板","prompt":"只读分析 TaskBoard 的任务状态流转","parent_task_id":"task_1","allowed_tools":["read_file","list_dir"]}
                    ],"final_answer":null}
                    """);
        }

        private ChatResponse subAgentResponse(String prompt) {
            if (prompt.contains("阅读运行时")) {
                return json("运行时报告：AgentRuntime 可以将子Agent结果投递回父Agent inbox。");
            }
            if (prompt.contains("阅读任务板")) {
                return json("任务板报告：TaskBoard 支持父任务从 IN_PROGRESS 进入 READY_FOR_REVIEW。");
            }
            return json("子Agent报告：完成。");
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
}
