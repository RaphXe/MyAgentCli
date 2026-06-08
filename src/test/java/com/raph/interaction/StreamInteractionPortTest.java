package com.raph.interaction;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamInteractionPortTest {
    @Test
    void readLinePrintsPromptAndReturnsInput() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamInteractionPort port = new StreamInteractionPort(
                new BufferedReader(new StringReader("hello\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        String value = port.readLine("prompt> ");

        assertEquals("hello", value);
        assertEquals("prompt> ", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void eofIsNormalizedToInteractionException() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamInteractionPort port = new StreamInteractionPort(
                new BufferedReader(new StringReader("")),
                new PrintStream(out, true, StandardCharsets.UTF_8)
        );

        InteractionException error = assertThrows(InteractionException.class,
                () -> port.readLine("prompt> "));

        assertEquals(InteractionException.Type.EOF, error.type());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("prompt> "));
    }
}
