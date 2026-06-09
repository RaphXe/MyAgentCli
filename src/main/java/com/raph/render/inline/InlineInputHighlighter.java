package com.raph.render.inline;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.regex.Pattern;

public final class InlineInputHighlighter implements Highlighter {
    private static final AttributedStyle SLASH_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BLUE)
            .bold();

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        if (buffer == null || buffer.isEmpty() || buffer.charAt(0) != '/') {
            return new AttributedString(buffer == null ? "" : buffer);
        }
        AttributedStringBuilder builder = new AttributedStringBuilder();
        builder.style(SLASH_STYLE);
        builder.append(buffer);
        builder.style(AttributedStyle.DEFAULT);
        return builder.toAttributedString();
    }

    @Override
    public void setErrorPattern(Pattern errorPattern) {
    }

    @Override
    public void setErrorIndex(int errorIndex) {
    }
}
