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
 * <p>Inline mode keeps generated output in scrollback while richer transient UI,
 * such as the status dock and thinking panel, is managed around the prompt.
 */
public class InlineRenderer extends PlainRenderer {
    private final Terminal terminal;
    private final PrintStream out;
    private final Object outputLock = new Object();
    private final BottomStatusBar statusBar;
    private final BlockRegistry blockRegistry = new BlockRegistry();
    private final ToolCallRenderer toolCallRenderer;
    private final CodeBlockRenderer codeBlockRenderer;
    private final InlineActivityDisplay activityDisplay;
    private final MarkdownStreamProcessor markdownStream = new MarkdownStreamProcessor();
    private final Object transcriptLock = new Object();
    private final List<TranscriptEntry> transcript = new ArrayList<>();
    private volatile LineReader lineReader;
    private volatile int lastColumns = -1;
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
        this.activityDisplay = new InlineActivityDisplay(terminal, this.out, outputLock);
        this.toolCallRenderer = new ToolCallRenderer(this.out, blockRegistry);
        this.codeBlockRenderer = new CodeBlockRenderer(this.out);
    }

    public void bindLineReader(LineReader lineReader) {
        this.lineReader = lineReader;
    }

    @Override
    public void beginTurn() {
        activityDisplay.end();
        synchronized (transcriptLock) {
            transcript.clear();
            renderedRows = 0;
            redrawing = false;
            markdownStream.reset();
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
        activityDisplay.end();
        FoldableBlock block = toolCallRenderer.createBlock(toolCalls);
        if (block == null) {
            return false;
        }
        blockRegistry.register(block);
        appendTranscriptEntry(new BlockEntry(block));
        return true;
    }

    @Override
    public void printPanel(String title, String body) {
        activityDisplay.end();
        appendTranscriptEntry(new PanelEntry(title, body));
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
        if (event.type() == RenderEvent.Type.STREAM_START) {
            activityDisplay.end();
            appendTranscriptText(event.text());
            markdownStream.reset();
            return;
        }
        if (event.type() == RenderEvent.Type.STREAM_DELTA) {
            activityDisplay.end();
            appendStreamText(event.text());
            return;
        }
        if (event.type() == RenderEvent.Type.STREAM_END) {
            activityDisplay.end();
            finishStreamText();
            return;
        }
        if (event.type() == RenderEvent.Type.TEAM_LOG || event.type() == RenderEvent.Type.ERROR) {
            activityDisplay.end();
            String text = event.text() == null ? "" : event.text();
            appendTranscriptEntry(new TextEntry(text + "\n"));
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

    private void ensureSizeStable() {
        if (redrawing) {
            return;
        }
        int columns = TerminalCapabilities.safeSize(terminal).getColumns();
        int prev = lastColumns;
        if (prev > 0 && prev != columns) {
            LineReader reader = activeReader();
            if (reader != null) {
                reader.callWidget(LineReader.REDRAW_LINE);
                if (statusBar != null) {
                    statusBar.beforeInput();
                }
            } else {
                redrawTranscript();
            }
        }
        lastColumns = columns;
    }

    private void printAbove(String text) {
        ensureSizeStable();
        LineReader reader = activeReader();
        if (reader != null) {
            synchronized (outputLock) {
                reader.printAbove(text == null ? "" : text);
            }
            return;
        }
        synchronized (outputLock) {
            out.print(text == null ? "" : text);
            out.flush();
        }
    }

    private void appendTranscriptText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        appendTranscriptEntry(new TextEntry(text));
    }

    private void appendStreamText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        markdownStream.append(text);
    }

    private void finishStreamText() {
        markdownStream.finish();
        appendTranscriptText("\n");
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
                synchronized (outputLock) {
                    reader.printAbove(snapshot.toString());
                    if (statusBar != null) {
                        statusBar.beforeInput();
                    }
                }
                return;
            }
            redrawing = true;
            try {
                synchronized (outputLock) {
                    if (renderedRows > 0) {
                        out.print(AnsiSeq.moveUp(renderedRows));
                    }
                    out.print('\r');
                    out.print(AnsiSeq.CLEAR_TO_EOS);
                    out.print(snapshot);
                    out.flush();
                }
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
            synchronized (outputLock) {
                reader.printAbove(rendered);
            }
            return;
        }
        synchronized (outputLock) {
            out.print(rendered);
            out.flush();
        }
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
            int width = com.raph.render.DisplayWidth.of(codePoint);
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

    private record PanelEntry(String title, String body) implements TranscriptEntry {
        @Override
        public String render() {
            String normalizedBody = body == null ? "" : body.stripTrailing();
            StringBuilder rendered = new StringBuilder();
            if (title != null && !title.isBlank()) {
                rendered.append("╭─ ").append(title.trim()).append(System.lineSeparator());
            }
            if (!normalizedBody.isBlank()) {
                String[] lines = normalizedBody.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                for (String line : lines) {
                    rendered.append(line).append(System.lineSeparator());
                }
            }
            if (title != null && !title.isBlank()) {
                rendered.append("╰").append(System.lineSeparator());
            }
            if (rendered.isEmpty()) {
                rendered.append(System.lineSeparator());
            }
            return rendered.toString();
        }
    }

    private final class MarkdownStreamProcessor {
        private final InlineStyler inlineStyler = new InlineStyler();
        private final StringBuilder pendingFenceCandidate = new StringBuilder();
        private final StringBuilder fenceHeader = new StringBuilder();
        private final StringBuilder codeLine = new StringBuilder();
        private final List<String> codeLines = new ArrayList<>();
        private boolean lineStart = true;
        private boolean collectingFenceHeader;
        private boolean inFence;
        private String language = "";

        void append(String text) {
            for (int i = 0; i < text.length(); i++) {
                appendChar(text.charAt(i));
            }
        }

        void finish() {
            if (inFence) {
                if (!codeLine.isEmpty()) {
                    codeLines.add(codeLine.toString());
                    codeLine.setLength(0);
                }
                finishCodeBlock();
                return;
            }
            if (collectingFenceHeader) {
                inlineStyler.append(fenceHeader.toString());
                fenceHeader.setLength(0);
                collectingFenceHeader = false;
            }
            flushPendingFenceCandidate();
            inlineStyler.finish();
        }

        void reset() {
            inlineStyler.reset();
            pendingFenceCandidate.setLength(0);
            fenceHeader.setLength(0);
            codeLine.setLength(0);
            codeLines.clear();
            lineStart = true;
            collectingFenceHeader = false;
            inFence = false;
            language = "";
        }

        private void appendChar(char ch) {
            if (inFence) {
                codeLine.append(ch);
                if (ch == '\n') {
                    processCodeLine();
                }
                return;
            }
            if (collectingFenceHeader) {
                fenceHeader.append(ch);
                if (ch == '\n') {
                    beginCodeBlock();
                }
                return;
            }
            if (lineStart) {
                pendingFenceCandidate.append(ch);
                String pending = pendingFenceCandidate.toString();
                if ("```".startsWith(pending) && pending.length() < 3) {
                    return;
                }
                if (pending.startsWith("```")) {
                    collectingFenceHeader = true;
                    fenceHeader.append(pending);
                    pendingFenceCandidate.setLength(0);
                    if (ch == '\n') {
                        beginCodeBlock();
                    }
                    return;
                }
                flushPendingFenceCandidate();
                return;
            }

            inlineStyler.append(ch);
            if (ch == '\n') {
                lineStart = true;
            }
        }

        private void beginCodeBlock() {
            language = fenceHeader.toString()
                    .replace("\r", "")
                    .replace("\n", "")
                    .substring(3)
                    .trim();
            fenceHeader.setLength(0);
            collectingFenceHeader = false;
            inFence = true;
            lineStart = true;
        }

        private void processCodeLine() {
            String line = codeLine.toString();
            codeLine.setLength(0);
            if (isFenceLine(line)) {
                finishCodeBlock();
                lineStart = true;
                return;
            }
            codeLines.add(line);
            lineStart = true;
        }

        private void finishCodeBlock() {
            FoldableBlock block = codeBlockRenderer.createBlock(language, codeLines);
            blockRegistry.register(block);
            appendTranscriptEntry(new BlockEntry(block));
            codeLines.clear();
            language = "";
            inFence = false;
            lineStart = true;
        }

        private void flushPendingFenceCandidate() {
            if (pendingFenceCandidate.isEmpty()) {
                return;
            }
            String pending = pendingFenceCandidate.toString();
            pendingFenceCandidate.setLength(0);
            inlineStyler.append(pending);
            lineStart = pending.endsWith("\n") || pending.endsWith("\r");
        }

        private boolean isFenceLine(String line) {
            return line != null && line.trim().startsWith("```");
        }
    }

    private final class InlineStyler {
        private final StringBuilder pendingStars = new StringBuilder();
        private final StringBuilder boldBuffer = new StringBuilder();
        private boolean inBold;

        void append(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            for (int i = 0; i < text.length(); i++) {
                append(text.charAt(i));
            }
        }

        void append(char ch) {
            if (ch == '*') {
                pendingStars.append(ch);
                if (pendingStars.length() == 2) {
                    toggleBold();
                }
                return;
            }
            flushPendingStars();
            if (inBold) {
                boldBuffer.append(ch);
                return;
            }
            appendTranscriptText(String.valueOf(ch));
        }

        void finish() {
            if (inBold) {
                appendTranscriptText("**" + boldBuffer);
                boldBuffer.setLength(0);
                inBold = false;
            }
            flushPendingStars();
        }

        void reset() {
            pendingStars.setLength(0);
            boldBuffer.setLength(0);
            inBold = false;
        }

        private void toggleBold() {
            pendingStars.setLength(0);
            if (inBold) {
                appendTranscriptText(AnsiSeq.BOLD_DARK_BLUE + boldBuffer + AnsiSeq.RESET);
                boldBuffer.setLength(0);
                inBold = false;
                return;
            }
            inBold = true;
        }

        private void flushPendingStars() {
            if (pendingStars.isEmpty()) {
                return;
            }
            String stars = pendingStars.toString();
            pendingStars.setLength(0);
            if (inBold) {
                boldBuffer.append(stars);
            } else {
                appendTranscriptText(stars);
            }
        }
    }
}
