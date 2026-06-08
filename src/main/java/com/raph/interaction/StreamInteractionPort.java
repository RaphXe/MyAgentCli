package com.raph.interaction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Stream-backed fallback interaction port.
 */
public class StreamInteractionPort implements InteractionPort {
    private final BufferedReader in;
    private final PrintStream out;

    public StreamInteractionPort() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
    }

    public StreamInteractionPort(BufferedReader in, PrintStream out) {
        this.in = in == null
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                : in;
        this.out = out == null ? System.out : out;
    }

    @Override
    public String readLine(String prompt) throws InteractionException {
        print(prompt);
        try {
            String value = in.readLine();
            if (value == null) {
                throw new InteractionException(InteractionException.Type.EOF, "输入流已关闭");
            }
            return value;
        } catch (IOException e) {
            throw new InteractionException(InteractionException.Type.IO, "读取输入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String readSecret(String prompt) throws InteractionException {
        return readLine(prompt);
    }

    @Override
    public void print(String text) {
        out.print(text == null ? "" : text);
        out.flush();
    }
}
