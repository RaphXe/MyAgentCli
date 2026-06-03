package com.raph.render;

import java.io.PrintStream;

/** 普通终端渲染器：直接写入 PrintStream。 */
public class PlainRenderer implements Renderer {
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
    public StreamHandle contentStream(String prefix) {
        return new PlainStreamHandle(out, prefix, 0);
    }

    @Override
    public StreamHandle previewStream(String prefix, int maxChars) {
        return new PlainStreamHandle(out, prefix, maxChars);
    }

    private static final class PlainStreamHandle implements StreamHandle {
        private final PrintStream out;
        private final String prefix;
        private final int maxChars;
        private int printedChars;
        private boolean contentStarted;
        private boolean truncated;

        private PlainStreamHandle(PrintStream out, String prefix, int maxChars) {
            this.out = out == null ? System.out : out;
            this.prefix = prefix == null ? "" : prefix;
            this.maxChars = Math.max(0, maxChars);
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty() || truncated) {
                return;
            }
            if (!contentStarted) {
                out.print(prefix);
                contentStarted = true;
            }
            String chunk = delta;
            if (maxChars > 0) {
                int remaining = maxChars - printedChars;
                if (remaining <= 0) {
                    out.print("...");
                    out.flush();
                    truncated = true;
                    return;
                }
                if (delta.length() > remaining) {
                    chunk = delta.substring(0, remaining);
                    truncated = true;
                }
            }
            out.print(chunk);
            printedChars += chunk.length();
            if (truncated) {
                out.print("...");
            }
            out.flush();
        }

        @Override
        public synchronized boolean hasContent() {
            return contentStarted;
        }

        @Override
        public synchronized void finish() {
            if (contentStarted) {
                out.println();
                out.flush();
                contentStarted = false;
            }
        }
    }
}
