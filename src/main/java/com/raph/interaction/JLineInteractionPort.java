package com.raph.interaction;

import com.raph.render.Renderer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JLine-backed interaction port used by the CLI.
 */
public class JLineInteractionPort implements InteractionPort {
    private final LineReader reader;
    private final Renderer renderer;

    public JLineInteractionPort(LineReader reader, Renderer renderer) {
        this.reader = reader;
        this.renderer = renderer;
    }

    @Override
    public String readLine(String prompt) throws InteractionException {
        return readLine(prompt, null);
    }

    @Override
    public String readLine(String prompt, String rightPrompt) throws InteractionException {
        try {
            if (rightPrompt == null || rightPrompt.isBlank()) {
                return reader.readLine(prompt == null ? "" : prompt);
            }
            return reader.readLine(prompt == null ? "" : prompt, rightPrompt, (Character) null, null);
        } catch (UserInterruptException e) {
            throw new InteractionException(InteractionException.Type.INTERRUPTED, "用户取消输入", e);
        } catch (EndOfFileException e) {
            throw new InteractionException(InteractionException.Type.EOF, "输入流已关闭", e);
        } catch (RuntimeException e) {
            throw new InteractionException(InteractionException.Type.IO, "读取输入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String readSecret(String prompt) throws InteractionException {
        try {
            return reader.readLine(prompt == null ? "" : prompt, '*');
        } catch (UserInterruptException e) {
            throw new InteractionException(InteractionException.Type.INTERRUPTED, "用户取消输入", e);
        } catch (EndOfFileException e) {
            throw new InteractionException(InteractionException.Type.EOF, "输入流已关闭", e);
        } catch (RuntimeException e) {
            throw new InteractionException(InteractionException.Type.IO, "读取输入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void print(String text) {
        if (renderer != null) {
            renderer.print(text == null ? "" : text);
        }
    }

    @Override
    public void printPanel(String title, String body) {
        if (renderer != null) {
            renderer.printPanel(title, body);
        } else {
            InteractionPort.super.printPanel(title, body);
        }
    }

    @Override
    public InterruptWatcher startInterruptWatch(AtomicBoolean interrupted) {
        if (reader == null || reader.getTerminal() == null) {
            return InterruptWatcher.NO_OP;
        }
        Terminal terminal = reader.getTerminal();

        Attributes savedAttrs = terminal.getAttributes();
        log("startWatch: saved=" + fmt(savedAttrs));

        Attributes rawAttrs = terminal.getAttributes();
        rawAttrs.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        rawAttrs.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        rawAttrs.setControlChar(Attributes.ControlChar.VMIN, 1);
        rawAttrs.setControlChar(Attributes.ControlChar.VTIME, 2);
        terminal.setAttributes(rawAttrs);
        log("startWatch: raw=" + fmt(terminal.getAttributes()));

        Thread monitor = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int b = System.in.read();
                    if (b == -1) break;
                    if (b == 0x07) {
                        log("Ctrl+G detected via System.in");
                        interrupted.set(true);
                        break;
                    }
                }
            } catch (IOException e) {
                log("monitor IO error: " + e.getMessage());
            }
        }, "interrupt-monitor");
        monitor.setDaemon(true);
        monitor.start();

        return () -> {
            log("stopWatch: restoring " + fmt(savedAttrs));
            terminal.setAttributes(savedAttrs);
            monitor.interrupt();
        };
    }

    private static String fmt(Attributes a) {
        if (a == null) return "null";
        try {
            var lf = a.getLocalFlags();
            return "ICANON=" + (lf != null && lf.contains(Attributes.LocalFlag.ICANON))
                    + " ECHO=" + (lf != null && lf.contains(Attributes.LocalFlag.ECHO))
                    + " VMIN=" + a.getControlChar(Attributes.ControlChar.VMIN)
                    + " VTIME=" + a.getControlChar(Attributes.ControlChar.VTIME);
        } catch (Exception e) {
            return "err:" + e.getMessage();
        }
    }

    private static void log(String msg) {
        try (PrintWriter pw = new PrintWriter(new FileWriter("/tmp/interrupt-debug.log", true))) {
            pw.println(Instant.now() + " [" + Thread.currentThread().getName() + "] " + msg);
            pw.flush();
        } catch (IOException ignored) {
        }
    }
}
