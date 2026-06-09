package com.raph;

import com.raph.cli.CliConfig;
import com.raph.cli.TuiSession;
import com.raph.agent.Agent;
import com.raph.agent.PlanExecuteAgent;
import com.raph.hitl.HitlToolRegistry;
import com.raph.hitl.TerminalHitlHandler;
import com.raph.interaction.InteractionPort;
import com.raph.interaction.JLineInteractionPort;
import com.raph.llm.LlmClient;
import com.raph.llm.LlmClientManager;
import com.raph.memory.MemoryManager;
import com.raph.mcp.MCPServerManager;
import com.raph.render.LightTuiRenderer;
import com.raph.render.PlainRenderer;
import com.raph.render.Renderer;
import com.raph.render.inline.InlineInputHighlighter;
import com.raph.render.inline.InlineRenderer;
import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class Main {

    public static void main(String[] args) {
        printBanner();

        CliConfig config = CliConfig.load();
        LlmClientManager llmClientManager = new LlmClientManager(config.llmConfig());
        LlmClient llmClient = llmClientManager;

        MemoryManager memoryManager = new MemoryManager(llmClient);
        memoryManager.init();

        Terminal terminal;
        LineReader reader;
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            LineReaderBuilder readerBuilder = LineReaderBuilder.builder()
                    .terminal(terminal);
            if (config.inlineTuiEnabled()) {
                readerBuilder.highlighter(new InlineInputHighlighter());
            }
            reader = readerBuilder.build();
        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);
            return;
        }

        Renderer renderer = createRenderer(config, terminal);
        if (renderer instanceof InlineRenderer inlineRenderer) {
            inlineRenderer.bindLineReader(reader);
            bindCtrlOToFoldableBlocks(reader, inlineRenderer);
        }
        renderer.start();

        InteractionPort interaction = new JLineInteractionPort(reader, renderer);

        TerminalHitlHandler hitlHandler = new TerminalHitlHandler(false, interaction);
        ToolRegistry toolRegistry = new HitlToolRegistry(hitlHandler);
        MCPServerManager mcpServerManager = MCPServerManager.fromDefaultConfig(toolRegistry, renderer::println);
        mcpServerManager.startAllAndRegisterTools();

        Agent agent = new Agent(llmClient, memoryManager, toolRegistry);
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, toolRegistry, config.outputTruncateLimit());

        try {
            if (llmClientManager.isConnected()) {
                renderer.println("✅ LLM 已连接: " + llmClientManager.status() + "\n");
            } else {
                renderer.println("⚠ 未配置 LLM。请使用 /connect <api_base> 连接 OpenAI-compatible provider。\n");
            }
            new TuiSession(interaction, renderer, llmClientManager, toolRegistry, hitlHandler,
                    memoryManager, mcpServerManager, agent, planAgent).run();
        } finally {
            mcpServerManager.close();
            renderer.close();
        }
    }

    private static Renderer createRenderer(CliConfig config, Terminal terminal) {
        if (config.inlineTuiEnabled()) {
            return new InlineRenderer(terminal);
        }
        if (config.lightTuiEnabled()) {
            return new LightTuiRenderer(System.out, terminal::getWidth);
        }
        return new PlainRenderer(System.out);
    }

    private static void bindCtrlOToFoldableBlocks(LineReader reader, InlineRenderer renderer) {
        if (reader == null || renderer == null) {
            return;
        }
        reader.getWidgets().put("paicli-toggle-foldable", () -> {
            renderer.toggleLastBlock();
            reader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference reference = new Reference("paicli-toggle-foldable");
        String ctrlO = String.valueOf((char) 15);
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> keyMap = reader.getKeyMaps().get(mapName);
            if (keyMap != null) {
                keyMap.bind(reference, ctrlO);
            }
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
