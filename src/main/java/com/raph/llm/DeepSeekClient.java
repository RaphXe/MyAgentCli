package com.raph.llm;

import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

public class DeepSeekClient extends AbstractOpenaiClient {
    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String MODEL = "deepseek-v4-pro";
    private final String apikey;
    private final OkHttpClient client;

    public DeepSeekClient(String apikey) {
        this.apikey = apikey;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    protected String getApiUrl() {
        return API_URL;
    }

    @Override
    protected String getModel() {
        return MODEL;
    }

    @Override
    protected String getApiKey() {
        return apikey;
    }
}
