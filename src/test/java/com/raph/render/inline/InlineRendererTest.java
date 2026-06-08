package com.raph.render.inline;

import com.raph.render.RenderEvent;
import com.raph.render.Renderer;
import com.raph.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InlineRendererTest {
    @Test
    void fallsBackToPlainOutputWithoutTerminal() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        renderer.start();
        renderer.emit(RenderEvent.status("普通模式 1/10"));
        renderer.emit(RenderEvent.activity("agent", "思考中"));
        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onContentDelta("hello");
        stream.finish();

        assertFalse(renderer.hasStatusBar());
        assertEquals("* ", renderer.inputPrompt("> "));
        String text = out.toString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("普通模式 1/10"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("[agent] 思考中"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("Agent: hello\n"), text);
    }

    @Test
    void rendersToolCallsAsFoldableBlocks() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        renderer.beginTurn();
        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onContentDelta("checking\n");
        boolean handled = renderer.appendToolCalls(List.of(
                new LlmClient.ToolCall("call-1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}"))
        ));
        renderer.toggleLastBlock();

        String text = out.toString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(handled);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("Agent: checking\n"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("⏵ ReadFile(README.md)"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("读取 1 个文件"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("└ README.md"), text);
    }

    @Test
    void registryTogglesLastBlockForTranscriptRedraw() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BlockRegistry registry = new BlockRegistry();
        registry.register(new FoldableBlock(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                "collapsed",
                List.of("expanded")
        ));

        boolean first = registry.toggleLastForRedraw();
        boolean second = registry.toggleLastForRedraw();

        org.junit.jupiter.api.Assertions.assertTrue(first);
        org.junit.jupiter.api.Assertions.assertTrue(second);
    }

    @Test
    void reasoningUsesThinkingPanelUntilContentStarts() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onReasoningDelta("分析项目结构");
        stream.onContentDelta("答案");

        String text = out.toString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("Thinking"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("分析项目结构"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("Agent: 答案"), text);
    }
}
