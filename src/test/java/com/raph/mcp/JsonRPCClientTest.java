package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonRPCClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void requestReturnsResult() throws Exception {
        FakeTransport transport = new FakeTransport("""
                {"jsonrpc":"2.0","id":1,"result":{"ok":true}}
                """);
        JsonRPCClient client = new JsonRPCClient(transport, Duration.ofSeconds(1));

        JsonNode result = client.request("ping", null);

        assertEquals(true, result.path("ok").asBoolean());
        assertEquals("ping", transport.lastMessage.path("method").asText());
    }

    @Test
    void requestRejectsJsonRpcError() {
        FakeTransport transport = new FakeTransport("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"missing"}}
                """);
        JsonRPCClient client = new JsonRPCClient(transport, Duration.ofSeconds(1));

        assertThrows(MCPException.class, () -> client.request("missing", null));
    }

    @Test
    void requestRejectsMismatchedId() {
        FakeTransport transport = new FakeTransport("""
                {"jsonrpc":"2.0","id":9,"result":{}}
                """);
        JsonRPCClient client = new JsonRPCClient(transport, Duration.ofSeconds(1));

        assertThrows(MCPException.class, () -> client.request("ping", null));
    }

    @Test
    void notificationDoesNotRequireResponse() throws Exception {
        FakeTransport transport = new FakeTransport(null);
        JsonRPCClient client = new JsonRPCClient(transport, Duration.ofSeconds(1));

        client.notify("notifications/initialized", null);

        assertEquals("notifications/initialized", transport.lastMessage.path("method").asText());
    }

    private static final class FakeTransport implements MCPTransport {
        private final String response;
        private JsonNode lastMessage;

        private FakeTransport(String response) {
            this.response = response;
        }

        @Override
        public void start() {
        }

        @Override
        public JsonNode send(JsonNode message, Duration timeout) throws MCPException {
            lastMessage = message;
            try {
                return response == null ? null : MAPPER.readTree(response);
            } catch (Exception e) {
                throw new MCPException("bad fake response", e);
            }
        }

        @Override
        public void close() {
        }
    }
}
