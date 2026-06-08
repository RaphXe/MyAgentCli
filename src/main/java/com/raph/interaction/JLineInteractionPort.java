package com.raph.interaction;

import com.raph.render.Renderer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

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
        try {
            return reader.readLine(prompt == null ? "" : prompt);
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
}
