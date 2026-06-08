package com.raph.render;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicLong;

/** 普通终端渲染器：直接写入 PrintStream。 */
public class PlainRenderer implements Renderer {
    private static final AtomicLong STREAM_SEQUENCE = new AtomicLong();

    private final PrintStream out;

    public PlainRenderer() {
        this(System.out);
    }

    public PlainRenderer(PrintStream out) {
        this.out = out == null ? System.out : out;
    }

    @Override
    public PrintStream stream() {
        return out;
    }

    @Override
    public void emit(RenderEvent event) {
        if (event == null) {
            return;
        }
        String text = event.text() == null ? "" : event.text();
        switch (event.type()) {
            case TEXT, STATUS, ACTIVITY, STREAM_START, STREAM_DELTA, TOKEN_USAGE -> out.print(text);
            case LINE, STREAM_END, TEAM_LOG, ERROR -> out.println(text);
            case PLAN_CREATED, PLAN_STARTED, PLAN_TASK_STARTED, PLAN_TASK_COMPLETED,
                    PLAN_TASK_FAILED, TOOL_STARTED, TOOL_FINISHED -> {
                // Structural events are consumed by richer renderers; existing plain text remains separate.
            }
        }
        out.flush();
    }

    @Override
    public StreamHandle contentStream(String prefix) {
        return new PlainStreamHandle(this, "content", prefix, 0);
    }

    @Override
    public StreamHandle previewStream(String prefix, int maxChars) {
        return new PlainStreamHandle(this, "preview", prefix, maxChars);
    }

    private static final class PlainStreamHandle implements StreamHandle {
        private final Renderer renderer;
        private final String scope;
        private final String streamId;
        private final String prefix;
        private final int maxChars;
        private int printedChars;
        private boolean contentStarted;
        private boolean truncated;

        private PlainStreamHandle(Renderer renderer, String scope, String prefix, int maxChars) {
            this.renderer = renderer;
            this.scope = scope == null || scope.isBlank() ? "content" : scope;
            this.streamId = this.scope + "-" + STREAM_SEQUENCE.incrementAndGet();
            this.prefix = prefix == null ? "" : prefix;
            this.maxChars = Math.max(0, maxChars);
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty() || truncated) {
                return;
            }
            if (!contentStarted) {
                renderer.emit(RenderEvent.streamStart(scope, streamId, prefix));
                contentStarted = true;
            }
            String chunk = delta;
            if (maxChars > 0) {
                int remaining = maxChars - printedChars;
                if (remaining <= 0) {
                    renderer.emit(RenderEvent.streamDelta(scope, streamId, "..."));
                    truncated = true;
                    return;
                }
                if (delta.length() > remaining) {
                    chunk = delta.substring(0, remaining);
                    truncated = true;
                }
            }
            renderer.emit(RenderEvent.streamDelta(scope, streamId, chunk));
            printedChars += chunk.length();
            if (truncated) {
                renderer.emit(RenderEvent.streamDelta(scope, streamId, "..."));
            }
        }

        @Override
        public synchronized boolean hasContent() {
            return contentStarted;
        }

        @Override
        public synchronized void finish() {
            if (contentStarted) {
                renderer.emit(RenderEvent.streamEnd(scope, streamId));
                contentStarted = false;
            }
        }
    }
}
