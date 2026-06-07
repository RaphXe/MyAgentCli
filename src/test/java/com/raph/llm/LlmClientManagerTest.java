package com.raph.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientManagerTest {
    @Test
    void disconnectedClientRejectsChatUntilConfigured() {
        LlmClientManager manager = new LlmClientManager();

        IOException error = assertThrows(IOException.class,
                () -> manager.chat(List.of(LlmClient.Message.user("hello")), List.of()));

        assertTrue(error.getMessage().contains("尚未连接"));
    }

    @Test
    void probesModelsFromOpenAiCompatibleEndpointAndSwitchesModel() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = """
                    {"object":"list","data":[{"id":"z-model"},{"id":"a-model"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            LlmClientManager manager = new LlmClientManager();

            List<String> models = manager.probeModels(baseUrl, "test-key");
            assertEquals(List.of("a-model", "z-model"), models);

            manager.connect(new LlmConfig(LlmConfig.OPENAI_COMPATIBLE, baseUrl, "test-key", "a-model"));
            manager.selectModel("z-model");

            assertTrue(manager.isConnected());
            assertTrue(manager.status().contains("z-model"));
        } finally {
            server.stop(0);
        }
    }
}
