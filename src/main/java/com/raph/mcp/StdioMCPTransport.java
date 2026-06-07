package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class StdioMCPTransport implements MCPTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final List<String> stderrLines = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> stdoutNonJsonLines = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> unmatchedResponses = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile String lastSentMethod;
    private volatile String lastFailure;

    private Process process;
    private BufferedWriter stdin;
    private Thread stdoutThread;
    private Thread stderrThread;

    public StdioMCPTransport(String command, List<String> args, Map<String, String> env) {
        this.command = command;
        this.args = args == null ? List.of() : List.copyOf(args);
        this.env = env == null ? Map.of() : Map.copyOf(env);
    }

    @Override
    public void start() throws MCPException {
        if (command == null || command.isBlank()) {
            throw new MCPException("stdio MCP server command is required");
        }
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.environment().putAll(env);
        try {
            process = builder.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdoutThread = new Thread(this::readStdout, "mcp-stdio-stdout-" + command);
            stderrThread = new Thread(this::readStderr, "mcp-stdio-stderr-" + command);
            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
            stdoutThread.start();
            stderrThread.start();
        } catch (IOException e) {
            throw new MCPException("Failed to start stdio MCP server: " + command, e);
        }
    }

    @Override
    public synchronized JsonNode send(JsonNode message, Duration timeout) throws MCPException {
        ensureStarted();
        lastSentMethod = message.path("method").asText("");
        try {
            stdin.write(message.toString());
            stdin.write("\n");
            stdin.flush();
        } catch (IOException e) {
            lastFailure = e.getMessage();
            throw new MCPException("Failed to write MCP stdio message: " + e.getMessage(), e);
        }

        JsonNode id = message.path("id");
        if (id.isMissingNode() || id.isNull()) {
            return null;
        }
        return waitForResponse(id, timeout == null ? Duration.ofSeconds(90) : timeout);
    }

    private JsonNode waitForResponse(JsonNode expectedId, Duration timeout) throws MCPException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remainingNanos = deadline - System.nanoTime();
            try {
                JsonNode response = messages.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (response == null) {
                    break;
                }
                JsonNode responseId = response.path("id");
                if (!responseId.isMissingNode() && matchesExpectedId(responseId, expectedId)) {
                    return response;
                }
                recordUnmatchedResponse(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastFailure = "interrupted while waiting for response";
                throw new MCPException("Interrupted while waiting for MCP stdio response", e);
            }
        }
        lastFailure = "timed out waiting for response";
        throw new MCPException("Timed out waiting for MCP stdio response. stderr=" + recentStderr());
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        messages.offer(MAPPER.readTree(trimmed));
                    } catch (Exception e) {
                        recordStdoutNonJson(trimmed);
                    }
                }
            }
        } catch (Exception e) {
            recordStderr("stdout reader stopped: " + e.getMessage());
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                recordStderr(line);
            }
        } catch (IOException e) {
            recordStderr("stderr reader stopped: " + e.getMessage());
        }
    }

    private void recordStderr(String line) {
        synchronized (stderrLines) {
            stderrLines.add(line);
            while (stderrLines.size() > 20) {
                stderrLines.remove(0);
            }
        }
    }

    private String recentStderr() {
        synchronized (stderrLines) {
            return String.join(" | ", stderrLines);
        }
    }

    private void recordStdoutNonJson(String line) {
        synchronized (stdoutNonJsonLines) {
            stdoutNonJsonLines.add(line);
            while (stdoutNonJsonLines.size() > 20) {
                stdoutNonJsonLines.remove(0);
            }
        }
    }

    private String recentStdoutNonJson() {
        synchronized (stdoutNonJsonLines) {
            return String.join(" | ", stdoutNonJsonLines);
        }
    }

    private void recordUnmatchedResponse(JsonNode response) {
        synchronized (unmatchedResponses) {
            unmatchedResponses.add(response.toString());
            while (unmatchedResponses.size() > 20) {
                unmatchedResponses.remove(0);
            }
        }
    }

    private String recentUnmatchedResponses() {
        synchronized (unmatchedResponses) {
            return String.join(" | ", unmatchedResponses);
        }
    }

    private boolean matchesExpectedId(JsonNode actualId, JsonNode expectedId) {
        if (actualId.isNumber() && expectedId.isNumber()) {
            return actualId.asLong() == expectedId.asLong();
        }
        return actualId.equals(expectedId);
    }

    @Override
    public String diagnostics() {
        List<String> commandLine = new ArrayList<>();
        if (command != null) {
            commandLine.add(command);
        }
        commandLine.addAll(args);
        String processState = process == null ? "not-started" : (process.isAlive() ? "alive" : "exited");
        String stderr = recentStderr();
        String stdoutNonJson = recentStdoutNonJson();
        String unmatched = recentUnmatchedResponses();
        return "transport=stdio"
                + ", command=" + commandLine
                + ", process=" + processState
                + ", lastMethod=" + blankToDefault(lastSentMethod, "none")
                + ", lastFailure=" + blankToDefault(lastFailure, "none")
                + ", unmatchedResponses=" + (unmatched.isBlank() ? "(empty)" : unmatched)
                + ", stdoutNonJson=" + (stdoutNonJson.isBlank() ? "(empty)" : stdoutNonJson)
                + ", stderr=" + (stderr.isBlank() ? "(empty)" : stderr);
    }

    @Override
    public String logs() {
        String stderr = recentStderr();
        return stderr.isBlank() ? "(empty)" : stderr;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private void ensureStarted() throws MCPException {
        if (process == null || stdin == null || !process.isAlive()) {
            throw new MCPException("MCP stdio transport is not running");
        }
    }

    @Override
    public void close() {
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
