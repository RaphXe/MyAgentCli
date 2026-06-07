package com.raph.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OpenAiCompatibleClient extends AbstractOpenaiClient {
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleClient(LlmConfig config) {
        if (config == null || config.baseUrl() == null || config.apiKey() == null || config.model() == null) {
            throw new IllegalArgumentException("OpenAI-compatible client requires baseUrl, apiKey and model");
        }
        this.baseUrl = normalizeBaseUrl(config.baseUrl());
        this.apiKey = config.apiKey();
        this.model = config.model();
    }

    public static List<String> listModels(String baseUrl, String apiKey) throws IOException {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("API key 不能为空");
        }
        Request request = new Request.Builder()
                .url(normalizedBaseUrl + "/models")
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .get()
                .build();

        try (Response response = SHARED_HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseBody = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("模型列表请求失败: HTTP " + response.code() + " " + response.message()
                        + (responseBody.isBlank() ? "" : ", body: " + responseBody));
            }
            if (responseBody.isBlank()) {
                return List.of();
            }

            JsonNode root = mapper.readTree(responseBody);
            JsonNode data = root.path("data");
            List<String> models = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.path("id").asText("");
                    if (!id.isBlank()) {
                        models.add(id);
                    }
                }
            }
            models.sort(Comparator.naturalOrder());
            return List.copyOf(models);
        }
    }

    private static String normalizeBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        String normalized = rawBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        return normalized;
    }

    @Override
    protected String getApiUrl() {
        return baseUrl + "/chat/completions";
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }
}
