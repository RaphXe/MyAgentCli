package com.raph.render;

import com.raph.agent.TeamView;
import com.raph.plan.PlanView;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Lightweight event-aware terminal renderer.
 */
public class LightTuiRenderer extends PlainRenderer implements ViewAwareRenderer {
    private static final int DEFAULT_WIDTH = 100;
    private static final int MIN_WIDTH = 56;
    private static final int MAX_WIDTH = 132;
    private static final int RECENT_ACTIVITY_LIMIT = 6;

    private final PrintStream out;
    private final IntSupplier widthSupplier;
    private final Deque<String> recentActivity = new ArrayDeque<>();
    private String lastStatus = "";
    private PlanView planView;
    private TeamView teamView;
    private Theme theme = Theme.LIGHT;

    public enum Theme {
        LIGHT,
        COMPACT
    }

    public LightTuiRenderer(PrintStream out) {
        this(out, () -> DEFAULT_WIDTH);
    }

    public LightTuiRenderer(PrintStream out, IntSupplier widthSupplier) {
        super(out);
        this.out = out == null ? System.out : out;
        this.widthSupplier = widthSupplier == null ? () -> DEFAULT_WIDTH : widthSupplier;
    }

    @Override
    public synchronized void start() {
        drawHeader();
    }

    @Override
    public synchronized void emit(RenderEvent event) {
        if (event == null) {
            return;
        }
        switch (event.type()) {
            case STATUS -> renderStatus(event.text());
            case ACTIVITY -> renderActivity(event.scope(), event.text());
            case PLAN_CREATED -> renderPlanCreated(event);
            case PLAN_STARTED -> renderActivity("plan", "开始执行计划");
            case PLAN_TASK_STARTED -> renderActivity("plan", "开始任务 " + event.id() + ": " + event.text());
            case PLAN_TASK_COMPLETED -> renderActivity("plan", "完成任务 " + event.id());
            case PLAN_TASK_FAILED -> renderActivity("plan", "任务失败 " + event.id() + ": " + event.text());
            case TOOL_STARTED -> renderActivity(event.scope(), "调用工具 " + event.id());
            case TOOL_FINISHED -> renderActivity(event.scope(), "工具完成 " + event.id());
            case TEAM_LOG -> renderTeamLog(event.text());
            case TOKEN_USAGE -> renderTokenUsage(event);
            case ERROR -> renderError(event.scope(), event.text());
            default -> super.emit(event);
        }
    }

    @Override
    public void updatePlanView(PlanView view) {
        this.planView = view;
    }

    @Override
    public void updateTeamView(TeamView view) {
        this.teamView = view;
    }

    public synchronized Theme theme() {
        return theme;
    }

    public synchronized boolean setTheme(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "light", "default", "spacious" -> {
                theme = Theme.LIGHT;
                return true;
            }
            case "compact", "dense" -> {
                theme = Theme.COMPACT;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void drawHeader() {
        int width = width();
        out.println("┌" + repeat("─", width - 2) + "┐");
        out.println(boxLine(" PaiCli Agent Workspace", width));
        out.println("└" + repeat("─", width - 2) + "┘");
        out.flush();
    }

    private void renderStatus(String rawStatus) {
        lastStatus = normalizeLine(rawStatus);
        int width = width();
        out.println("┌─ Status " + repeat("─", Math.max(0, width - 11)) + "┐");
        printBoxLines(" " + lastStatus, width);
        if (theme == Theme.COMPACT) {
            out.println("└" + repeat("─", width - 2) + "┘");
            out.flush();
            return;
        }
        if (planView != null && !planView.id().isBlank()) {
            printBoxLines(" Plan " + planView.status() + " tasks "
                    + planView.completedTasks() + "/" + planView.totalTasks()
                    + " failed " + planView.failedTasks(), width);
        }
        if (teamView != null && !teamView.status().isBlank()) {
            printBoxLines(" Team " + teamView.status() + " round "
                    + teamView.currentRound() + "/" + teamView.maxRounds()
                    + " steps " + teamView.agentSteps() + "/" + teamView.maxAgentSteps()
                    + " active " + teamView.activeTasks(), width);
        }
        out.println("└" + repeat("─", width - 2) + "┘");
        out.flush();
    }

    private void renderActivity(String scope, String text) {
        String line = "[" + blankToDefault(scope, "activity") + "] " + normalizeLine(text);
        rememberActivity(line);
        out.println("› " + truncateToWidth(line, width() - 2));
        out.flush();
    }

    private void renderPlanCreated(RenderEvent event) {
        Map<String, String> metadata = event.metadata();
        String goal = blankToDefault(metadata.get("goal"), event.id());
        String taskCount = blankToDefault(metadata.get("tasks"), "?");
        String summary = blankToDefault(metadata.get("summary"), "计划已生成");
        int width = width();
        out.println("┌─ Plan " + repeat("─", Math.max(0, width - 9)) + "┐");
        printBoxLines(" Goal: " + goal, width);
        printBoxLines(" Tasks: " + taskCount + " | " + summary, width);
        out.println("└" + repeat("─", width - 2) + "┘");
        out.flush();
    }

    private void renderTeamLog(String text) {
        if (text == null || text.isBlank()) {
            out.println();
            out.flush();
            return;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String rawLine : lines) {
            String line = stripFramePrefix(rawLine);
            if (line.isBlank()) {
                out.println("│ team │");
                continue;
            }
            int contentWidth = width() - displayWidth("│ team │ ");
            for (String chunk : wrapToWidth(line, contentWidth)) {
                out.println("│ team │ " + chunk);
            }
        }
        out.flush();
    }

    private void renderTokenUsage(RenderEvent event) {
        Map<String, String> metadata = event.metadata();
        String total = metadata.get("total");
        String context = metadata.get("context");
        if (total == null && context == null) {
            super.emit(event);
            return;
        }
        renderActivity("tokens", "total=" + blankToDefault(total, "?")
                + " context=" + blankToDefault(context, "?"));
    }

    private void renderError(String scope, String text) {
        out.println("! " + truncateToWidth("[" + blankToDefault(scope, "error") + "] " + normalizeLine(text), width() - 2));
        out.flush();
    }

    private void rememberActivity(String line) {
        recentActivity.addLast(line);
        while (recentActivity.size() > RECENT_ACTIVITY_LIMIT) {
            recentActivity.removeFirst();
        }
    }

    private int width() {
        int value;
        try {
            value = widthSupplier.getAsInt();
        } catch (RuntimeException e) {
            value = DEFAULT_WIDTH;
        }
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, value <= 0 ? DEFAULT_WIDTH : value));
    }

    private static String boxLine(String text, int width) {
        String content = truncateToWidth(text == null ? "" : text, width - 2);
        return "│" + padRightToWidth(content, width - 2) + "│";
    }

    private void printBoxLines(String text, int width) {
        for (String line : boxLines(text, width)) {
            out.println(line);
        }
    }

    private static java.util.List<String> boxLines(String text, int width) {
        java.util.List<String> result = new java.util.ArrayList<>();
        int contentWidth = Math.max(12, width - 2);
        for (String chunk : wrapToWidth(text == null ? "" : text, contentWidth)) {
            result.add("│" + padRightToWidth(chunk, contentWidth) + "│");
        }
        return result;
    }

    private static String normalizeLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String stripFramePrefix(String text) {
        if (text == null) {
            return "";
        }
        String value = text.stripLeading();
        while (value.startsWith("│")) {
            value = value.substring(1).stripLeading();
        }
        if (value.startsWith("┌─")) {
            value = value.substring(2).replaceFirst("^─+\\s*", "").stripLeading();
        } else if (value.startsWith("└─")) {
            value = value.substring(2).replaceFirst("^─+\\s*", "").stripLeading();
        }
        return value.stripTrailing();
    }

    private static java.util.List<String> wrapToWidth(String value, int maxWidth) {
        if (value == null || value.isBlank()) {
            return java.util.List.of("");
        }
        int width = Math.max(12, maxWidth);
        java.util.List<String> lines = new java.util.ArrayList<>();
        String remaining = value.trim();
        while (displayWidth(remaining) > width) {
            int breakAt = findBreakPointByWidth(remaining, width);
            lines.add(remaining.substring(0, breakAt).stripTrailing());
            remaining = remaining.substring(breakAt).stripLeading();
        }
        if (!remaining.isEmpty()) {
            lines.add(remaining);
        }
        return lines;
    }

    private static int findBreakPointByWidth(String value, int width) {
        int breakAt = indexForDisplayWidth(value, width);
        for (int i = breakAt; i > Math.max(0, breakAt - 24); i--) {
            if (Character.isWhitespace(value.charAt(i - 1))) {
                return i;
            }
        }
        return breakAt;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String truncateToWidth(String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        if (maxWidth <= 0 || displayWidth(value) <= maxWidth) {
            return value;
        }
        if (maxWidth <= 3) {
            return value.substring(0, indexForDisplayWidth(value, maxWidth));
        }
        return value.substring(0, indexForDisplayWidth(value, maxWidth - 3)) + "...";
    }

    private static String padRightToWidth(String value, int width) {
        String truncated = truncateToWidth(value, width);
        int displayWidth = displayWidth(truncated);
        if (displayWidth >= width) {
            return truncated;
        }
        return truncated + " ".repeat(width - displayWidth);
    }

    private static int indexForDisplayWidth(String value, int maxWidth) {
        int width = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int charWidth = charDisplayWidth(codePoint);
            if (width + charWidth > maxWidth) {
                break;
            }
            width += charWidth;
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static int displayWidth(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            width += charDisplayWidth(codePoint);
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static int charDisplayWidth(int codePoint) {
        if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
            return 1;
        }
        if (Character.isISOControl(codePoint)) {
            return 0;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
            return 2;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS
                || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS
                || block == Character.UnicodeBlock.EMOTICONS
                || block == Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS) {
            return 2;
        }
        return 1;
    }

    private static String repeat(String value, int count) {
        return value.repeat(Math.max(0, count));
    }
}
