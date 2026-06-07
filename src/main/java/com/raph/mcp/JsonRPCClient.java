package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public class JsonRPCClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MCPTransport transport;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Duration requestTimeout;

    public JsonRPCClient(MCPTransport transport, Duration requestTimeout) {
        this.transport = transport;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(90) : requestTimeout;
    }

    public void start() throws MCPException {
        transport.start();
    }

    public synchronized JsonNode request(String method, JsonNode params) throws MCPException {
        long id = nextId.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null && !params.isMissingNode() && !params.isNull()) {
            request.set("params", params);
        }

        JsonNode response = transport.send(request, requestTimeout);
        if (response == null || !response.isObject()) {
            throw new MCPException("JSON-RPC response is empty for method: " + method);
        }
        JsonNode responseId = response.path("id");
        if (!responseId.isNumber() || responseId.asLong() != id) {
            throw new MCPException("JSON-RPC response id mismatch for method: " + method);
        }
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new MCPException("JSON-RPC error for " + method + ": " + error);
        }
        return response.path("result");
    }

    public synchronized void notify(String method, JsonNode params) throws MCPException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null && !params.isMissingNode() && !params.isNull()) {
            notification.set("params", params);
        }
        transport.send(notification, requestTimeout);
    }

    public String diagnostics() {
        return transport.diagnostics();
    }

    @Override
    public void close() {
        transport.close();
    }
}
