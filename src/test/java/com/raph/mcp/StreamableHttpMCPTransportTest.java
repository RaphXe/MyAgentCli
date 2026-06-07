package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamableHttpMCPTransportTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void preservesSessionAndParsesSseResponse() throws Exception {
        AtomicBoolean sawSessionHeaders = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(exchange, sawSessionHeaders));
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            JsonRPCClient client = new JsonRPCClient(
                    new StreamableHttpMCPTransport(url, Map.of()),
                    Duration.ofSeconds(2)
            );

            JsonNode init = client.request("initialize", object("protocolVersion", "2025-06-18"));
            JsonNode list = client.request("tools/list", null);

            assertEquals("2025-06-18", init.path("protocolVersion").asText());
            assertEquals("echo", list.path("tools").get(0).path("name").asText());
            assertTrue(sawSessionHeaders.get());
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, AtomicBoolean sawSessionHeaders) throws IOException {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        long id = request.path("id").asLong();
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        ObjectNode result = response.putObject("result");

        if ("initialize".equals(method)) {
            result.put("protocolVersion", "2025-06-18");
            result.putObject("capabilities");
            result.putObject("serverInfo").put("name", "fake-http");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
            write(exchange, response.toString());
            return;
        }

        if ("tools/list".equals(method)) {
            sawSessionHeaders.set("session-1".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))
                    && "2025-06-18".equals(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version")));
            result.putArray("tools").addObject()
                    .put("name", "echo")
                    .put("description", "Echo");
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            write(exchange, "event: message\n"
                    + "data: " + response + "\n\n");
            return;
        }

        exchange.sendResponseHeaders(404, 0);
        exchange.close();
    }

    private static ObjectNode object(String key, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(key, value);
        return node;
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
