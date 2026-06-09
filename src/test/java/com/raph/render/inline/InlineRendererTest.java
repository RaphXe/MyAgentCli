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
    void panelEntriesParticipateInTranscriptRedraw() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        renderer.beginTurn();
        renderer.printPanel("HITL 审批请求", "┌────┐\n│ ok │\n└────┘");
        renderer.appendToolCalls(List.of(
                new LlmClient.ToolCall("call-1",
                        new LlmClient.ToolCall.Function("execute_command", "{\"command\":\"pwd\"}"))
        ));
        renderer.toggleLastBlock();

        String text = out.toString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("╭─ HITL 审批请求"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("│ ok │"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("Shell(pwd)"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("执行 1 条命令"), text);
    }

    @Test
    void streamingCodeBlockIsFoldedIntoTranscriptBlock() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        renderer.beginTurn();
        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onContentDelta("这里是代码:\n```java\nclass Demo {\n  void run() {}\n}\n```\n完成");
        stream.finish();
        renderer.toggleLastBlock();

        String text = out.toString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("这里是代码:"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("⏵ Code java · 3 lines"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("  ```java"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("  class Demo {"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("完成"), text);
    }

    @Test
    void boldMarkdownContentRendersAsBoldDarkBlue() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        renderer.beginTurn();
        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onContentDelta("请关注 **重要内容** 好的");
        stream.finish();

        String text = out.toString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(
                text.contains(AnsiSeq.BOLD_DARK_BLUE + "重要内容" + AnsiSeq.RESET),
                text
        );
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("**重要内容**"), text);
    }

    @Test
    void boldMarkdownInsideCodeBlockIsNotStyled() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        renderer.beginTurn();
        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onContentDelta("```text\n**literal**\n```\n");
        stream.finish();
        renderer.toggleLastBlock();

        String text = out.toString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("  **literal**"), text);
        org.junit.jupiter.api.Assertions.assertFalse(text.contains(AnsiSeq.BOLD_DARK_BLUE), text);
    }

    @Test
    void streamingDiffBlockUsesDiffSummaryRenderer() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        renderer.beginTurn();
        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onContentDelta("""
                ```diff
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -1 +1,2 @@
                -old
                +new
                +next
                ```
                """);
        stream.finish();
        renderer.toggleLastBlock();

        String text = out.toString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("⏵ Diff diff · 1 files · +2 -1 · 7 lines"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("  Diff diff · 1 files · +2 -1"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("  + new"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("  - old"), text);
    }

    @Test
    void longStreamingCodeBlockIsMarkedAsFoldedLongOutput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );
        StringBuilder code = new StringBuilder("```text\n");
        for (int i = 0; i < 13; i++) {
            code.append("line ").append(i).append('\n');
        }
        code.append("```\n");

        renderer.beginTurn();
        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onContentDelta(code.toString());
        stream.finish();

        String text = out.toString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(
                text.contains("⏵ Code text · 13 lines · folded long output"),
                text
        );
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("line 12"), text);
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

    @Test
    void thinkingPanelFitsWideCharactersByDisplayWidth() {
        String value = "  │ 12. `mcp__everything_http__simulate-research-query` - 模拟研究查询";

        String fitted = InlineActivityDisplay.fit(value, 48);

        org.junit.jupiter.api.Assertions.assertTrue(InlineActivityDisplay.displayWidth(fitted) <= 48, fitted);
        org.junit.jupiter.api.Assertions.assertTrue(fitted.endsWith("..."), fitted);
    }
}
