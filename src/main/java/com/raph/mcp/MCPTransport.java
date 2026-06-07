package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

public interface MCPTransport extends AutoCloseable {
    void start() throws MCPException;

    JsonNode send(JsonNode message, Duration timeout) throws MCPException;

    default String diagnostics() {
        return "";
    }

    @Override
    void close();
}
