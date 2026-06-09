package com.raph.hitl;

import com.raph.interaction.InteractionException;
import com.raph.interaction.InteractionPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalHitlHandlerTest {
    @Test
    void approvalRequestIsRenderedAsPanel() {
        RecordingInteraction interaction = new RecordingInteraction("y");
        TerminalHitlHandler handler = new TerminalHitlHandler(true, interaction);

        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("execute_command", "{\"command\":\"pwd\"}", "需要查看当前目录")
        );

        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
        assertEquals(1, interaction.panelCount);
        assertEquals("HITL 审批请求", interaction.lastPanelTitle);
        assertTrue(interaction.lastPanelBody.contains("execute_command"), interaction.lastPanelBody);
        assertTrue(interaction.lastRightPrompt.contains("approve"), interaction.lastRightPrompt);
    }

    private static final class RecordingInteraction implements InteractionPort {
        private final Queue<String> inputs = new ArrayDeque<>();
        private int panelCount;
        private String lastPanelTitle;
        private String lastPanelBody;
        private String lastRightPrompt;

        private RecordingInteraction(String... inputs) {
            this.inputs.addAll(java.util.List.of(inputs));
        }

        @Override
        public String readLine(String prompt) throws InteractionException {
            return inputs.isEmpty() ? "" : inputs.remove();
        }

        @Override
        public String readLine(String prompt, String rightPrompt) throws InteractionException {
            lastRightPrompt = rightPrompt;
            return readLine(prompt);
        }

        @Override
        public String readSecret(String prompt) throws InteractionException {
            return readLine(prompt);
        }

        @Override
        public void print(String text) {
        }

        @Override
        public void printPanel(String title, String body) {
            panelCount++;
            lastPanelTitle = title;
            lastPanelBody = body;
        }
    }
}
