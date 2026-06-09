package com.raph.render.inline;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

final class InlineDiffRenderer {
    private final PrintStream out;

    InlineDiffRenderer(PrintStream out) {
        this.out = out == null ? System.out : out;
    }

    FoldableBlock createBlock(String language, List<String> lines) {
        List<String> normalized = normalize(lines);
        int additions = 0;
        int deletions = 0;
        int files = 0;
        for (String line : normalized) {
            if (line.startsWith("diff --git ")) {
                files++;
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        String label = language == null || language.isBlank() ? "diff" : language.trim();
        String fileSummary = files > 0 ? " · " + files + " files" : "";
        String header = "⏵ Diff " + label + fileSummary
                + " · +" + additions + " -" + deletions
                + " · " + normalized.size() + " lines (ctrl+o to expand)";

        List<String> expanded = new ArrayList<>();
        expanded.add("  Diff " + label + fileSummary + " · +" + additions + " -" + deletions);
        expanded.add("  ```" + label);
        for (String line : normalized) {
            expanded.add("  " + decorate(line));
        }
        expanded.add("  ```");
        return new FoldableBlock(out, header, expanded);
    }

    static boolean isDiffLanguage(String language) {
        if (language == null) {
            return false;
        }
        String normalized = language.trim().toLowerCase();
        return normalized.equals("diff")
                || normalized.equals("patch")
                || normalized.equals("udiff")
                || normalized.equals("unified-diff");
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

    private static String decorate(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        if (line.startsWith("+") && !line.startsWith("+++")) {
            return "+ " + line.substring(1);
        }
        if (line.startsWith("-") && !line.startsWith("---")) {
            return "- " + line.substring(1);
        }
        return "  " + line;
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
