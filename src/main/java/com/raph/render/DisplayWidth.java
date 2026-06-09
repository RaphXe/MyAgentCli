package com.raph.render;

/**
 * Shared CJK-aware terminal display-width calculation.
 *
 * <p>Consolidates logic that was duplicated across LightTuiRenderer, InlineRenderer,
 * and InlineActivityDisplay.
 */
public final class DisplayWidth {
    private DisplayWidth() {
    }

    /** Display width in terminal columns (CJK ≈ 2, ASCII ≈ 1, control chars ≈ 0). */
    public static int of(int codePoint) {
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
        // Emoji / pictograph ranges commonly rendered as 2 columns
        if ((codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
                || (codePoint >= 0xFF01 && codePoint <= 0xFF60)) {
            return 2;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.EMOTICONS
                || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS
                || block == Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS) {
            return 2;
        }
        return 1;
    }

    /** Total display width of a string. */
    public static int of(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int width = 0;
        int i = 0;
        while (i < value.length()) {
            int cp = value.codePointAt(i);
            width += of(cp);
            i += Character.charCount(cp);
        }
        return width;
    }

    private static final String ELLIPSIS = "...";
    private static final int ELLIPSIS_WIDTH = of(ELLIPSIS);

    /**
     * Returns the substring of {@code value} whose display width does not exceed
     * {@code maxColumns}. If truncation occurs, appends "...".
     */
    public static String truncate(String value, int maxColumns) {
        if (value == null) {
            return "";
        }
        if (maxColumns <= 0) {
            return "";
        }
        int fullWidth = of(value);
        if (fullWidth <= maxColumns) {
            return value;
        }
        int available = maxColumns - ELLIPSIS_WIDTH;
        if (available <= 0) {
            return rawTruncate(value, maxColumns);
        }
        return rawTruncate(value, available) + ELLIPSIS;
    }

    /** Fit a line into {@code columns}, right-padding to fill. */
    public static String fit(String value, int columns) {
        if (value == null || value.isBlank()) {
            return columns > 0 ? " ".repeat(columns) : "";
        }
        if (columns <= 0) {
            return value;
        }
        if (of(value) <= columns) {
            return value + " ".repeat(columns - of(value));
        }
        return truncate(value, columns);
    }

    /** Right-pad a string so its display width equals {@code targetWidth}. */
    public static String padRight(String value, int targetWidth) {
        String truncated = truncate(value, targetWidth);
        int current = of(truncated);
        if (current >= targetWidth) {
            return truncated;
        }
        return truncated + " ".repeat(targetWidth - current);
    }

    /** Fit a line into {@code columns}, right-padding to fill. */
    public static String fit(String value, int columns) {
        if (value == null || value.isBlank()) {
            return columns > 0 ? " ".repeat(columns) : "";
        }
        if (columns <= 0) {
            return value;
        }
        if (of(value) <= columns) {
            return value + " ".repeat(columns - of(value));
        }
        return truncate(value, columns);
    }

    private static String rawTruncate(String value, int maxColumns) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < value.length()) {
            int cp = value.codePointAt(i);
            int w = of(cp);
            if (used + w > maxColumns) {
                break;
            }
            result.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return result.toString();
    }
}
