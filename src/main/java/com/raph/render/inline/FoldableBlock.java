package com.raph.render.inline;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

final class FoldableBlock {
    private final PrintStream out;
    private final String collapsedHeader;
    private final List<String> expandedLines;
    private final String collapseFooter;
    private boolean expanded;
    private int renderedLineCount;
    private boolean frozen;

    FoldableBlock(PrintStream out, String collapsedHeader, List<String> expandedLines) {
        this(out, collapsedHeader, expandedLines, "⏷ collapse (ctrl+o)");
    }

    FoldableBlock(PrintStream out, String collapsedHeader, List<String> expandedLines, String collapseFooter) {
        this.out = out == null ? System.out : out;
        this.collapsedHeader = collapsedHeader == null ? "" : collapsedHeader;
        this.expandedLines = List.copyOf(expandedLines == null ? List.of() : expandedLines);
        this.collapseFooter = collapseFooter == null ? "" : collapseFooter;
    }

    void renderInitial() {
        synchronized (out) {
            out.println(collapsedHeader);
            renderedLineCount = 1;
            out.flush();
        }
    }

    boolean toggle() {
        if (frozen) {
            return false;
        }
        synchronized (out) {
            out.print(AnsiSeq.moveUp(renderedLineCount));
            out.print('\r');
            out.print(AnsiSeq.CLEAR_TO_EOS);
            List<String> lines = expanded ? collapsedLines() : expandedLinesWithFooter();
            for (String line : lines) {
                out.println(line);
            }
            renderedLineCount = Math.max(1, lines.size());
            expanded = !expanded;
            out.flush();
            return true;
        }
    }

    boolean toggleStateForRedraw() {
        expanded = !expanded;
        renderedLineCount = Math.max(1, currentLines().size());
        return true;
    }

    void freeze() {
        frozen = true;
    }

    List<String> currentLines() {
        return expanded ? expandedLinesWithFooter() : collapsedLines();
    }

    private List<String> collapsedLines() {
        return List.of(collapsedHeader);
    }

    private List<String> expandedLinesWithFooter() {
        if (collapseFooter.isBlank()) {
            return expandedLines;
        }
        List<String> lines = new ArrayList<>(expandedLines);
        lines.add(collapseFooter);
        return lines;
    }
}
