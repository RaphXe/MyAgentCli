package com.raph.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.List;

/**
 * JLine-managed bottom status dock for inline mode.
 */
final class BottomStatusBar implements AutoCloseable {
    private final Terminal terminal;
    private Status status;
    private String lastStatus = "";
    private boolean started;
    private boolean closed;

    BottomStatusBar(Terminal terminal) {
        this.terminal = terminal;
    }

    synchronized void start() {
        if (started || closed) {
            return;
        }
        status = Status.getStatus(terminal);
        if (status != null) {
            status.setBorder(true);
        }
        started = true;
        render();
    }

    synchronized void update(String statusText) {
        lastStatus = normalize(statusText);
        render();
    }

    synchronized void beforeInput() {
        render();
    }

    synchronized void afterInput() {
        render();
    }

    private void render() {
        if (!started || closed || status == null || lastStatus.isBlank()) {
            return;
        }
        int columns = Math.max(40, TerminalCapabilities.safeSize(terminal).getColumns());
        status.update(lines(columns));
    }

    private List<AttributedString> lines(int columns) {
        String main = fit(" " + lastStatus, columns);
        String hint = fit(" * message  /help  /status  /tools  /plan  /team", columns);
        return List.of(
                new AttributedString(main, AttributedStyle.DEFAULT),
                new AttributedString(hint, AttributedStyle.DEFAULT.faint())
        );
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Status dock = status;
        status = null;
        if (dock != null) {
            dock.clear();
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String fit(String text, int columns) {
        if (columns <= 0) {
            return "";
        }
        String value = text == null ? "" : text;
        if (value.length() > columns) {
            return value.substring(0, columns);
        }
        return value + " ".repeat(columns - value.length());
    }
}
