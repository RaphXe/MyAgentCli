package com.raph;

import com.raph.cli.CliConfig;
import com.raph.cli.TuiSession;
import com.raph.agent.Agent;
import com.raph.agent.PlanExecuteAgent;
import com.raph.hitl.HitlToolRegistry;
import com.raph.hitl.TerminalHitlHandler;
import com.raph.llm.DeepSeekClient;
import com.raph.llm.LlmClient;
import com.raph.memory.MemoryManager;
import com.raph.mcp.MCPServerManager;
import com.raph.render.PlainRenderer;
import com.raph.render.Renderer;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class Main {

    public static void main(String[] args) {
        printBanner();

        CliConfig config = CliConfig.load();
        if (config.apiKey() == null || config.apiKey().isEmpty()) {
            System.err.println("❌ 错误: 未找到 API_KEY");
            System.exit(1);
        }

        LlmClient llmClient = new DeepSeekClient(config.apiKey());
        TerminalHitlHandler hitlHandler = new TerminalHitlHandler(false);
        ToolRegistry toolRegistry = new HitlToolRegistry(hitlHandler);

        MemoryManager memoryManager = new MemoryManager(llmClient);
        memoryManager.init();

        Renderer renderer = new PlainRenderer(System.out);
        renderer.start();
        MCPServerManager mcpServerManager = MCPServerManager.fromDefaultConfig(toolRegistry, renderer::println);
        mcpServerManager.startAllAndRegisterTools();

        Agent agent = new Agent(llmClient, memoryManager, toolRegistry);
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, toolRegistry, config.outputTruncateLimit());

        LineReader reader;
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();
        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            new TuiSession(reader, renderer, llmClient, toolRegistry, hitlHandler,
                    memoryManager, mcpServerManager, agent, planAgent).run();
        } finally {
            mcpServerManager.close();
        }
    }

    private static void printBanner() {
        System.out.println("""
        ╔══════════════════════════════════════════════════════════╗
        ║   ██████╗  █████╗ ██╗      ██████╗██╗     ██╗            ║
        ║   ██╔══██╗██╔══██╗██║     ██╔════╝██║     ██║            ║
        ║   ██████╔╝███████║██║     ██║     ██║     ██║            ║
        ║   ██╔═══╝ ██╔══██║██║     ██║     ██║     ██║            ║
        ║   ██║     ██║  ██║███████╗╚██████╗███████╗██║            ║
        ║   ╚═╝     ╚═╝  ╚═╝╚══════╝ ╚═════╝╚══════╝╚═╝            ║
        ║              简单的 Java Agent CLI v1.0.0                ║
        ╚══════════════════════════════════════════════════════════╝
        """);
    }
}
