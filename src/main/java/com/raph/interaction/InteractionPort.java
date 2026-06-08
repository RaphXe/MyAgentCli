package com.raph.interaction;

/**
 * User interaction boundary for terminal-style prompts.
 */
public interface InteractionPort {
    String readLine(String prompt) throws InteractionException;

    default String readLine(String prompt, String rightPrompt) throws InteractionException {
        return readLine(prompt);
    }

    String readSecret(String prompt) throws InteractionException;

    void print(String text);

    default void println(String text) {
        print((text == null ? "" : text) + System.lineSeparator());
    }

    default boolean confirm(String prompt) throws InteractionException {
        String value = readLine(prompt);
        return value != null && ("y".equalsIgnoreCase(value.trim()) || "yes".equalsIgnoreCase(value.trim()));
    }
}
