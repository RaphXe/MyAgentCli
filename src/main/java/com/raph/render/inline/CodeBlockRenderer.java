package com.raph.render.inline;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

final class CodeBlockRenderer {
    private static final int LONG_BLOCK_LINES = 12;
    private static final int LONG_BLOCK_CHARS = 1200;

    private final PrintStream out;
    private final InlineDiffRenderer diffRenderer;

    CodeBlockRenderer(PrintStream out) {
        this.out = out == null ? System.out : out;
        this.diffRenderer = new InlineDiffRenderer(this.out);
    }

    FoldableBlock createBlock(String language, List<String> lines) {
        String label = normalizeLanguage(language);
        if (InlineDiffRenderer.isDiffLanguage(label) || looksLikeDiff(lines)) {
            return diffRenderer.createBlock(label.isBlank() ? "diff" : label, lines);
        }

        List<String> normalized = normalize(lines);
        int chars = normalized.stream().mapToInt(String::length).sum();
        boolean longBlock = normalized.size() >= LONG_BLOCK_LINES || chars >= LONG_BLOCK_CHARS;
        String header = "⏵ Code " + (label.isBlank() ? "block" : label)
                + " · " + normalized.size() + " lines"
                + (longBlock ? " · folded long output" : "")
                + " (ctrl+o to expand)";

        List<String> expanded = new ArrayList<>();
        expanded.add("  ```" + label);
        for (String line : normalized) {
            expanded.add("  " + line);
        }
        expanded.add("  ```");
        return new FoldableBlock(out, header, expanded);
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "";
        }
        return language.trim().split("\\s+", 2)[0].toLowerCase();
    }

    private static List<String> normalize(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(lines.size());
        for (String line : lines) {
            normalized.add(stripLineEnding(line));
        }
        return normalized;
    }

    private static boolean looksLikeDiff(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        int signals = 0;
        for (String line : lines) {
            String stripped = stripLineEnding(line);
            if (stripped.startsWith("diff --git ")
                    || stripped.startsWith("@@ ")
                    || stripped.startsWith("+++ ")
                    || stripped.startsWith("--- ")) {
                signals++;
            }
        }
        return signals >= 2;
    }

    private static String stripLineEnding(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }
}
