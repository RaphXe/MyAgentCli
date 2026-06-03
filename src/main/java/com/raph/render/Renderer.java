package com.raph.render;

import com.raph.llm.LlmClient;

import java.io.PrintStream;

/**
 * CLI 输出渲染抽象。
 *
 * 先提供 plain 终端实现，把普通 Agent、Plan task、Multi-Agent 的流式输出统一收口。
 * 后续如果要做 JLine inline/TUI/status bar，只需要替换 Renderer 实现，Agent 执行链不再直接依赖 System.out。
 */
public interface Renderer extends AutoCloseable {
    default void start() {
    }

    PrintStream stream();

    default void print(String text) {
        stream().print(text);
        stream().flush();
    }

    default void println(String text) {
        stream().println(text);
        stream().flush();
    }

    default void printf(String format, Object... args) {
        stream().printf(format, args);
        stream().flush();
    }

    StreamHandle contentStream(String prefix);

    StreamHandle previewStream(String prefix, int maxChars);

    @Override
    default void close() {
    }

    interface StreamHandle extends LlmClient.StreamListener {
        boolean hasContent();

        void finish();
    }
}
