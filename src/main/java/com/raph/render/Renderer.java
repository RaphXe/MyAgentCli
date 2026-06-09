package com.raph.render;

import com.raph.llm.LlmClient;

import java.io.PrintStream;
import java.util.List;

/**
 * CLI 输出渲染抽象。
 *
 * 先提供 plain 终端实现，把普通 Agent、Plan task、Multi-Agent 的流式输出统一收口。
 * 后续如果要做 JLine inline/TUI/status bar，只需要替换 Renderer 实现，Agent 执行链不再直接依赖 System.out。
 */
public interface Renderer extends AutoCloseable {
    default void start() {
    }

    default void beginTurn() {
    }

    default void beforeInput() {
    }

    default void afterInput() {
    }

    default String inputPrompt(String fallbackPrompt) {
        return fallbackPrompt == null ? "" : fallbackPrompt;
    }

    default String inputRightPrompt() {
        return null;
    }

    default boolean supportsThinkingPanel() {
        return false;
    }

    default void beginThinking(String label) {
    }

    default void appendThinking(String delta) {
    }

    default void endThinking() {
    }

    PrintStream stream();

    default void print(String text) {
        emit(RenderEvent.text(text));
    }

    default void println(String text) {
        emit(RenderEvent.line(text));
    }

    default void printPanel(String title, String body) {
        if (title != null && !title.isBlank()) {
            println(title);
        }
        println(body == null ? "" : body);
    }

    default void printf(String format, Object... args) {
        print(String.format(format, args));
    }

    default void emit(RenderEvent event) {
        if (event == null) {
            return;
        }
        String text = event.text() == null ? "" : event.text();
        switch (event.type()) {
            case LINE, STREAM_END -> stream().println(text);
            default -> stream().print(text);
        }
        stream().flush();
    }

    StreamHandle contentStream(String prefix);

    StreamHandle previewStream(String prefix, int maxChars);

    default boolean appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        return false;
    }

    @Override
    default void close() {
    }

    interface StreamHandle extends LlmClient.StreamListener {
        boolean hasContent();

        default boolean onToolCalls(List<LlmClient.ToolCall> toolCalls) {
            return false;
        }

        void finish();
    }
}
