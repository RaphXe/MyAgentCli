package com.raph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class StreamableHttpMCPTransport implements MCPTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    private final String url;
    private final Map<String, String> headers;
    private final OkHttpClient httpClient;
    private String sessionId;
    private String protocolVersion = "2025-06-18";
    private volatile String lastMethod;
    private volatile int lastStatusCode;
    private volatile String lastFailure;

    public StreamableHttpMCPTransport(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void start() throws MCPException {
        if (url == null || url.isBlank()) {
            throw new MCPException("streamable_http MCP server url is required");
        }
    }

    @Override
    public JsonNode send(JsonNode message, Duration timeout) throws MCPException {
        lastMethod = message.path("method").asText("");
        RequestBody body = RequestBody.create(message.toString(), JSON);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .post(body);
        headers.forEach(builder::header);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
            builder.header("MCP-Protocol-Version", protocolVersion);
        }

        OkHttpClient client = timeout == null
                ? httpClient
                : httpClient.newBuilder().callTimeout(timeout).build();
        try (Response response = client.newCall(builder.build()).execute()) {
            lastStatusCode = response.code();
            String returnedSession = response.header("Mcp-Session-Id");
            if (returnedSession != null && !returnedSession.isBlank()) {
                sessionId = returnedSession;
            }
            if (response.code() == 202) {
                return null;
            }
            ResponseBody responseBody = response.body();
            String payload = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                lastFailure = "HTTP " + response.code();
                throw new MCPException("MCP HTTP request failed: HTTP " + response.code() + " " + payload);
            }
            if (payload.isBlank()) {
                return null;
            }
            String contentType = response.header("Content-Type", "");
            JsonNode parsed = contentType.contains("text/event-stream")
                    ? parseSseResponse(payload, message.path("id"))
                    : MAPPER.readTree(payload);
            JsonNode result = parsed.path("result");
            JsonNode version = result.path("protocolVersion");
            if (version.isTextual() && !version.asText().isBlank()) {
                protocolVersion = version.asText();
            }
            return parsed;
        } catch (IOException e) {
            lastFailure = e.getMessage();
            throw new MCPException("MCP HTTP transport failed: " + e.getMessage(), e);
        }
    }

    private JsonNode parseSseResponse(String payload, JsonNode expectedId) throws IOException, MCPException {
        StringBuilder eventData = new StringBuilder();
        for (String line : payload.split("\\R")) {
            if (line.startsWith("data:")) {
                if (!eventData.isEmpty()) {
                    eventData.append('\n');
                }
                eventData.append(line.substring("data:".length()).trim());
                continue;
            }
            if (line.isBlank() && !eventData.isEmpty()) {
                JsonNode event = MAPPER.readTree(eventData.toString());
                if (matchesExpectedId(event, expectedId)) {
                    return event;
                }
                eventData.setLength(0);
            }
        }
        if (!eventData.isEmpty()) {
            JsonNode event = MAPPER.readTree(eventData.toString());
            if (matchesExpectedId(event, expectedId)) {
                return event;
            }
        }
        throw new MCPException("MCP SSE response did not include matching JSON-RPC response id=" + expectedId);
    }

    private boolean matchesExpectedId(JsonNode event, JsonNode expectedId) {
        if (expectedId == null || expectedId.isMissingNode() || expectedId.isNull()) {
            return true;
        }
        JsonNode actualId = event.path("id");
        if (actualId.isNumber() && expectedId.isNumber()) {
            return actualId.asLong() == expectedId.asLong();
        }
        return actualId.equals(expectedId);
    }

    @Override
    public String diagnostics() {
        return "transport=streamable_http"
                + ", url=" + url
                + ", sessionId=" + blankToDefault(sessionId, "none")
                + ", protocolVersion=" + protocolVersion
                + ", lastMethod=" + blankToDefault(lastMethod, "none")
                + ", lastStatusCode=" + lastStatusCode
                + ", lastFailure=" + blankToDefault(lastFailure, "none");
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
