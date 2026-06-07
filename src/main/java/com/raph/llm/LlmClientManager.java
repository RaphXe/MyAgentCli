package com.raph.llm;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class LlmClientManager implements LlmClient {
    private volatile LlmConfig config;
    private volatile LlmClient delegate;

    public LlmClientManager() {
    }

    public LlmClientManager(LlmConfig config) {
        if (config != null && config.isComplete()) {
            connect(config);
        }
    }

    public synchronized void connect(LlmConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig");
        if (!newConfig.isComplete()) {
            throw new IllegalArgumentException("LLM 配置不完整，需要 baseUrl、apiKey 和 model");
        }
        if (!LlmConfig.OPENAI_COMPATIBLE.equalsIgnoreCase(newConfig.provider())) {
            throw new IllegalArgumentException("当前阶段仅支持 openai-compatible provider");
        }
        this.delegate = new OpenAiCompatibleClient(newConfig);
        this.config = newConfig;
    }

    public synchronized List<String> probeModels(String baseUrl, String apiKey) throws IOException {
        return OpenAiCompatibleClient.listModels(baseUrl, apiKey);
    }

    public synchronized void selectModel(String model) {
        if (!isConnected()) {
            throw new IllegalStateException("尚未连接 LLM provider，请先使用 /connect <api_base>");
        }
        connect(config.withModel(model));
    }

    public boolean isConnected() {
        return delegate != null && config != null;
    }

    public LlmConfig config() {
        return config;
    }

    public String status() {
        if (!isConnected()) {
            return "未连接";
        }
        return config.provider() + " " + config.model() + " @ " + config.baseUrl();
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        LlmClient current = delegate;
        if (current == null) {
            throw new IOException("尚未连接 LLM provider，请先使用 /connect <api_base>");
        }
        return current.chat(messages, tools, listener);
    }
}
