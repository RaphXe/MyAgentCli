package com.raph.render;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlainRendererEventTest {
    @Test
    void streamHandleEmitsStructuredStreamEventsAndPlainText() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RecordingRenderer renderer = new RecordingRenderer(new PrintStream(out, true, StandardCharsets.UTF_8));

        Renderer.StreamHandle stream = renderer.contentStream("Agent: ");
        stream.onContentDelta("hello");
        stream.onContentDelta(" world");
        stream.finish();

        assertEquals("Agent: hello world\n", out.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(
                RenderEvent.Type.STREAM_START,
                RenderEvent.Type.STREAM_DELTA,
                RenderEvent.Type.STREAM_DELTA,
                RenderEvent.Type.STREAM_END
        ), renderer.eventTypes());
    }

    @Test
    void structuralEventsDoNotDuplicatePlainTextOutput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlainRenderer renderer = new PlainRenderer(new PrintStream(out, true, StandardCharsets.UTF_8));

        renderer.emit(RenderEvent.planTaskStarted("T1", "read files"));
        renderer.println("visible");

        assertEquals("visible\n", out.toString(StandardCharsets.UTF_8));
    }

    private static final class RecordingRenderer extends PlainRenderer {
        private final List<RenderEvent.Type> eventTypes = new ArrayList<>();

        private RecordingRenderer(PrintStream out) {
            super(out);
        }

        @Override
        public void emit(RenderEvent event) {
            eventTypes.add(event.type());
            super.emit(event);
        }

        private List<RenderEvent.Type> eventTypes() {
            return List.copyOf(eventTypes);
        }
    }
}
