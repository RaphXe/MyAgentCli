package com.raph.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

/**
 * Conservative terminal capability checks for inline rendering.
 */
public final class TerminalCapabilities {
    private TerminalCapabilities() {
    }

    public static boolean supportsAnsi(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        String type = terminal.getType();
        if (type != null && type.equalsIgnoreCase("dumb")) {
            return false;
        }
        String envTerm = System.getenv("TERM");
        return envTerm == null || !envTerm.equalsIgnoreCase("dumb");
    }

    public static boolean supportsStatusDock(Terminal terminal) {
        if (!supportsAnsi(terminal)) {
            return false;
        }
        if (Boolean.parseBoolean(System.getenv("PAICLI_NO_STATUSBAR"))
                || Boolean.parseBoolean(System.getProperty("paicli.no.statusbar"))) {
            return false;
        }
        Size size = safeSize(terminal);
        return size.getRows() >= 5 && size.getColumns() >= 40;
    }

    public static Size safeSize(Terminal terminal) {
        try {
            Size size = terminal == null ? null : terminal.getSize();
            if (size == null || size.getRows() <= 0 || size.getColumns() <= 0) {
                return new Size(80, 24);
            }
            return size;
        } catch (RuntimeException e) {
            return new Size(80, 24);
        }
    }
}
