package com.raph.render.inline;

import com.raph.render.PlainRenderer;
import com.raph.render.RenderEvent;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Inline, scrollback-preserving renderer.
 *
 * <p>M1 scope: JLine-managed status dock, inline prompt hints, and lifecycle hooks.
 * Foldable transcript blocks and inline HITL are intentionally left for later milestones.
 */
public class InlineRenderer extends PlainRenderer {
    private final Terminal terminal;
    private final PrintStream out;
    private final BottomStatusBar statusBar;
    private final BlockRegistry blockRegistry = new BlockRegistry();
    private final ToolCallRenderer toolCallRenderer;
    private final InlineActivityDisplay activityDisplay;
    private final Object transcriptLock = new Object();
    private final List<TranscriptEntry> transcript = new ArrayList<>();
    private volatile LineReader lineReader;
    private int renderedRows;
    private boolean redrawing;
    private boolean started;
    private boolean closed;

    public InlineRenderer(Terminal terminal) {
        this(terminal, System.out);
    }

    InlineRenderer(Terminal terminal, PrintStream out) {
        super(out);
        this.terminal = terminal;
        this.out = out == null ? System.out : out;
        this.statusBar = TerminalCapabilities.supportsStatusDock(terminal)
                ? new BottomStatusBar(terminal)
                : null;
        this.activityDisplay = new InlineActivityDisplay(terminal, this.out);
        this.toolCallRenderer = new ToolCallRenderer(this.out, blockRegistry);
    }

    public void bindLineReader(LineReader lineReader) {
        this.lineReader = lineReader;
    }

    @Override
    public void beginTurn() {
        synchronized (transcriptLock) {
            transcript.clear();
            renderedRows = 0;
            redrawing = false;
        }
        blockRegistry.clear();
    }

    @Override
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        if (statusBar != null) {
            statusBar.start();
        }
        started = true;
    }

    @Override
    public void beforeInput() {
        if (statusBar != null) {
            statusBar.beforeInput();
        }
    }

    @Override
    public void afterInput() {
        if (statusBar != null) {
            statusBar.afterInput();
        }
    }

    @Override
    public String inputPrompt(String fallbackPrompt) {
        return "* ";
    }

    @Override
    public String inputRightPrompt() {
        return "message / slash command";
    }

    @Override
    public boolean appendToolCalls(List<com.raph.llm.LlmClient.ToolCall> toolCalls) {
        FoldableBlock block = toolCallRenderer.createBlock(toolCalls);
        if (block == null) {
            return false;
        }
        blockRegistry.register(block);
        appendTranscriptEntry(new BlockEntry(block));
        return true;
    }

    @Override
    public boolean supportsThinkingPanel() {
        return true;
    }

    @Override
    public void beginThinking(String label) {
        activityDisplay.begin(label);
    }

    @Override
    public void appendThinking(String delta) {
        activityDisplay.append(delta);
    }

    @Override
    public void endThinking() {
        activityDisplay.end();
    }

    @Override
    public synchronized void emit(RenderEvent event) {
        if (event == null) {
            return;
        }
        if (event.type() == RenderEvent.Type.STATUS) {
            if (statusBar != null) {
                statusBar.update(event.text());
                return;
            }
            super.emit(event);
            return;
        }
        if (event.type() == RenderEvent.Type.ACTIVITY) {
            printAbove("› [" + blankToDefault(event.scope(), "activity") + "] "
                    + normalizeLine(event.text()) + "\n");
            return;
        }
        if (event.type() == RenderEvent.Type.STREAM_START || event.type() == RenderEvent.Type.STREAM_DELTA) {
            appendTranscriptText(event.text());
            return;
        }
        if (event.type() == RenderEvent.Type.STREAM_END) {
            appendTranscriptText("\n");
            return;
        }
        super.emit(event);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (statusBar != null) {
            statusBar.close();
        }
        activityDisplay.close();
        super.close();
    }

    public boolean hasStatusBar() {
        return statusBar != null;
    }

    public boolean toggleLastBlock() {
        if (!blockRegistry.toggleLastForRedraw()) {
            return false;
        }
        redrawTranscript();
        return true;
    }

    public void clearBlocks() {
        blockRegistry.clear();
    }

    private void printAbove(String text) {
        LineReader reader = activeReader();
        if (reader != null) {
            reader.printAbove(text == null ? "" : text);
            return;
        }
        out.print(text == null ? "" : text);
        out.flush();
    }

    private void appendTranscriptText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        appendTranscriptEntry(new TextEntry(text));
    }

    private void appendTranscriptEntry(TranscriptEntry entry) {
        if (entry == null) {
            return;
        }
        String rendered = entry.render();
        synchronized (transcriptLock) {
            transcript.add(entry);
            renderedRows += estimateRows(rendered);
            writeRendered(rendered);
        }
    }

    private void redrawTranscript() {
        synchronized (transcriptLock) {
            if (transcript.isEmpty()) {
                return;
            }
            StringBuilder snapshot = new StringBuilder();
            int rowsAfter = 0;
            for (TranscriptEntry entry : transcript) {
                String rendered = entry.render();
                snapshot.append(rendered);
                rowsAfter += estimateRows(rendered);
            }
            LineReader reader = activeReader();
            if (reader != null) {
                renderedRows = rowsAfter;
                reader.printAbove(snapshot.toString());
                if (statusBar != null) {
                    statusBar.beforeInput();
                }
                return;
            }
            redrawing = true;
            try {
                if (renderedRows > 0) {
                    out.print(AnsiSeq.moveUp(renderedRows));
                }
                out.print('\r');
                out.print(AnsiSeq.CLEAR_TO_EOS);
                out.print(snapshot);
                out.flush();
                renderedRows = rowsAfter;
            } finally {
                redrawing = false;
            }
        }
    }

    private void writeRendered(String rendered) {
        if (rendered == null || rendered.isEmpty()) {
            return;
        }
        LineReader reader = activeReader();
        if (reader != null && !redrawing) {
            reader.printAbove(rendered);
            return;
        }
        out.print(rendered);
        out.flush();
    }

    private int estimateRows(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int columns = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns());
        int rows = 0;
        int col = 0;
        boolean sawVisible = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint == '\u001B') {
                i = skipAnsi(text, i);
                continue;
            }
            if (codePoint == '\r') {
                col = 0;
                i += Character.charCount(codePoint);
                continue;
            }
            if (codePoint == '\n') {
                rows++;
                col = 0;
                sawVisible = false;
                i += Character.charCount(codePoint);
                continue;
            }
            int width = displayWidth(codePoint);
            if (width > 0) {
                sawVisible = true;
                col += width;
                if (col >= columns) {
                    rows++;
                    col = 0;
                    sawVisible = false;
                }
            }
            i += Character.charCount(codePoint);
        }
        if (sawVisible) {
            rows++;
        }
        return rows;
    }

    private static int skipAnsi(String text, int escIndex) {
        int i = escIndex + 1;
        if (i < text.length() && text.charAt(i) == '[') {
            i++;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c >= '@' && c <= '~') {
                    return i + 1;
                }
                i++;
            }
        }
        return escIndex + 1;
    }

    private static int displayWidth(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return 0;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
                || (codePoint >= 0xFF01 && codePoint <= 0xFF60)) {
            return 2;
        }
        return 1;
    }

    private LineReader activeReader() {
        LineReader reader = lineReader;
        if (reader == null || closed) {
            return null;
        }
        try {
            return reader.isReading() ? reader : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String normalizeLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private interface TranscriptEntry {
        String render();
    }

    private record TextEntry(String text) implements TranscriptEntry {
        @Override
        public String render() {
            return text == null ? "" : text;
        }
    }

    private record BlockEntry(FoldableBlock block) implements TranscriptEntry {
        @Override
        public String render() {
            return String.join(System.lineSeparator(), block.currentLines()) + System.lineSeparator();
        }
    }
}
