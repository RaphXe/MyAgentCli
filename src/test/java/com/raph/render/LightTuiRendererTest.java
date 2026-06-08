package com.raph.render;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LightTuiRendererTest {
    @Test
    void rendersHeaderStatusActivityAndPlanSummaryBlocks() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LightTuiRenderer renderer = new LightTuiRenderer(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                () -> 72
        );

        renderer.start();
        renderer.emit(RenderEvent.status("普通模式 [====] 10/100 10.0%"));
        renderer.emit(RenderEvent.activity("agent", "思考中"));
        renderer.emit(RenderEvent.planCreated("plan-1", "full plan")
                .withMetadata("goal", "改进 TUI")
                .withMetadata("summary", "建立轻量布局")
                .withMetadata("tasks", "3"));
        renderer.emit(RenderEvent.toolStarted("plan:T1", "read_file"));
        renderer.emit(RenderEvent.teamLog("Round 1 started"));

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("PaiCli Agent Workspace"), text);
        assertTrue(text.contains("Status"), text);
        assertTrue(text.contains("[agent] 思考中"), text);
        assertTrue(text.contains("Goal: 改进 TUI"), text);
        assertTrue(text.contains("Tasks: 3 | 建立轻量布局"), text);
        assertTrue(text.contains("调用工具 read_file"), text);
        assertTrue(text.contains("│ team │ Round 1 started"), text);
    }

    @Test
    void streamOutputStillRendersLikePlainText() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LightTuiRenderer renderer = new LightTuiRenderer(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                () -> 72
        );

        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onContentDelta("hello");
        stream.finish();

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Agent: hello\n"));
    }

    @Test
    void teamLogWrapsLongMultilineSummariesAndStripsNestedFramePrefixes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LightTuiRenderer renderer = new LightTuiRenderer(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                () -> 56
        );

        renderer.emit(RenderEvent.teamLog("""
                ┌─ Round 5 ─────────────────────────
                │ 任务板快照：
                │   task_1 [APPROVED] 分析当前项目的组织架构
                │   ★ final: 项目组织架构分析已完成。PaiCli 是一个 Java 17 / Maven 单模块命令行工具，包含 CLI、Agent、MCP、Memory、渲染管线和 HITL 审批。
                ✅ 团队输出：
                项目组织架构分析已完成。PaiCli 是一个 Java 17 / Maven 单模块命令行工具，包含 CLI、Agent、MCP、Memory、渲染管线和 HITL 审批。
                """));
        renderer.emit(RenderEvent.status("团队模式 10/100 10.0%"));

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("│ team │ Round 5"), text);
        assertTrue(text.contains("│ team │ 任务板快照："), text);
        assertTrue(text.contains("│ team │ task_1 [APPROVED] 分析当前项目的组织架构"), text);
        assertTrue(text.contains("CLI、Agent、MCP"), text);
        assertTrue(text.contains("渲染管线和 HITL 审批"), text);
        assertTrue(text.contains("\n┌─ Status"), text);
    }

    @Test
    void wideCharactersDoNotOverflowStatusOrTeamColumns() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LightTuiRenderer renderer = new LightTuiRenderer(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                () -> 56
        );

        renderer.emit(RenderEvent.status("📊 普通模式 [████████████████████] 0/1.0M 0.0% | conv=0, memory=0, compressed=0"));
        renderer.emit(RenderEvent.teamLog("│   - **技能系统**：基于 `SKILL.md` 的提示词注入，内置 5 个技能 (agent, team-agent, subagent, task, local-file-tools)"));

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("compressed=0"), text);
        for (String line : text.split("\n")) {
            assertTrue(displayWidth(line) <= 56, "line overflowed: " + line + " width=" + displayWidth(line));
            if (line.contains("team")) {
                assertTrue(line.startsWith("│ team │"), "team continuation lost prefix: " + line);
            }
        }
    }

    private static int displayWidth(String value) {
        int width = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            width += charDisplayWidth(codePoint);
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static int charDisplayWidth(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return 0;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
            return 2;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS
                || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS
                || block == Character.UnicodeBlock.EMOTICONS
                || block == Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS) {
            return 2;
        }
        return 1;
    }
}
