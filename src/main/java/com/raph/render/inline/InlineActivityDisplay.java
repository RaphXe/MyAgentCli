package com.raph.render.inline;

import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class InlineActivityDisplay implements AutoCloseable {
    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final int MAX_REASONING_CHARS = 4096;
    private static final int MAX_REASONING_ROWS = 4;

    private final Terminal terminal;
    private final PrintStream out;
    private final ScheduledExecutorService scheduler;
    private final StringBuilder reasoning = new StringBuilder();
    private ScheduledFuture<?> tickTask;
    private boolean active;
    private boolean closed;
    private String label = "Thinking";
    private long startedNanos;
    private int frame;
    private int renderedRows;

    InlineActivityDisplay(Terminal terminal, PrintStream out) {
        this.terminal = terminal;
        this.out = out == null ? System.out : out;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "inline-thinking");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized void begin(String label) {
        if (closed) {
            return;
        }
        clearLocked();
        reasoning.setLength(0);
        this.label = label == null || label.isBlank() ? "Thinking" : label.trim();
        this.startedNanos = System.nanoTime();
        this.frame = 0;
        this.active = true;
        renderLocked();
        restartTickLocked();
    }

    synchronized void append(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
        }
        if (!active) {
            begin("Thinking");
        }
        reasoning.append(delta);
        if (reasoning.length() > MAX_REASONING_CHARS) {
            reasoning.delete(0, reasoning.length() - MAX_REASONING_CHARS);
        }
        renderLocked();
    }

    synchronized void end() {
        if (closed) {
            return;
        }
        active = false;
        cancelTickLocked();
        reasoning.setLength(0);
        clearLocked();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        cancelTickLocked();
        clearLocked();
        scheduler.shutdownNow();
    }

    private void restartTickLocked() {
        cancelTickLocked();
        tickTask = scheduler.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    private void cancelTickLocked() {
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
    }

    private void tick() {
        synchronized (this) {
            if (!active || closed) {
                return;
            }
            frame++;
            renderLocked();
        }
    }

    private void renderLocked() {
        if (!active || closed) {
            return;
        }
        synchronized (out) {
            clearRenderedArea();
            List<String> lines = buildLines();
            for (String line : lines) {
                out.print(line);
                out.print(AnsiSeq.CLEAR_TO_EOL);
                out.println();
            }
            renderedRows = lines.size();
            out.flush();
        }
    }

    private List<String> buildLines() {
        int columns = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
        List<String> lines = new ArrayList<>();
        lines.add(fit("  " + spinner() + " " + label + "... (" + elapsedSeconds() + "s)", columns));
        List<String> quoteLines = reasoningLines();
        int start = Math.max(0, quoteLines.size() - MAX_REASONING_ROWS);
        for (int i = start; i < quoteLines.size(); i++) {
            lines.add(fit("  │ " + quoteLines.get(i), columns));
        }
        return lines;
    }

    private List<String> reasoningLines() {
        String content = reasoning.toString()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (content.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : content.split("\\R+")) {
            String normalized = rawLine.replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private void clearLocked() {
        synchronized (out) {
            clearRenderedArea();
            out.flush();
        }
    }

    private void clearRenderedArea() {
        if (renderedRows <= 0) {
            return;
        }
        out.print(AnsiSeq.moveUp(renderedRows));
        out.print('\r');
        out.print(AnsiSeq.CLEAR_TO_EOS);
        renderedRows = 0;
    }

    private String spinner() {
        return FRAMES[Math.floorMod(frame, FRAMES.length)];
    }

    private long elapsedSeconds() {
        return Math.max(0L, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos));
    }

    private static String fit(String value, int columns) {
        if (value == null) {
            return "";
        }
        if (value.length() <= columns) {
            return value;
        }
        return columns <= 3 ? value.substring(0, columns) : value.substring(0, columns - 3) + "...";
    }
}
