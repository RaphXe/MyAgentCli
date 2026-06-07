package com.raph.mcp;

import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MCPServerManager implements AutoCloseable {
    private static final int DEFAULT_INIT_CONCURRENCY = 4;
    private static final int DEFAULT_INIT_TIMEOUT_SECONDS = 30;

    private final List<MCPServerConfig> configs;
    private final ToolRegistry toolRegistry;
    private final Consumer<String> logger;
    private final int initConcurrency;
    private final Duration initTimeout;
    private final Map<String, MCPServerConfig> configByName = new LinkedHashMap<>();
    private final Map<String, ServerRuntime> runtimes = new LinkedHashMap<>();
    private ExecutorService executor;

    public MCPServerManager(List<MCPServerConfig> configs, ToolRegistry toolRegistry, Consumer<String> logger) {
        this.configs = configs == null ? List.of() : List.copyOf(configs);
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.logger = logger == null ? ignored -> {} : logger;
        this.initConcurrency = Math.max(1, readIntProperty("paicli.mcp.init.concurrency", DEFAULT_INIT_CONCURRENCY));
        this.initTimeout = Duration.ofSeconds(Math.max(1,
                readIntProperty("paicli.mcp.init.timeout.seconds", DEFAULT_INIT_TIMEOUT_SECONDS)));
        for (MCPServerConfig config : this.configs) {
            configByName.put(config.name(), config);
            runtimes.put(config.name(), ServerRuntime.initial(config));
        }
    }

    public static MCPServerManager fromDefaultConfig(ToolRegistry toolRegistry, Consumer<String> logger) {
        Path configPath = Path.of(System.getProperty("paicli.mcp.config", ".agents/mcp.json"));
        try {
            return new MCPServerManager(MCPServerConfig.load(configPath), toolRegistry, logger);
        } catch (IOException e) {
            Consumer<String> safeLogger = logger == null ? ignored -> {} : logger;
            safeLogger.accept("⚠ MCP 配置读取失败: " + configPath + " - " + e.getMessage());
            return new MCPServerManager(List.of(), toolRegistry, logger);
        }
    }

    public synchronized InitSummary startAllAndRegisterTools() {
        if (configs.isEmpty()) {
            return new InitSummary(0, 0, List.of());
        }
        int poolSize = Math.min(configs.size(), initConcurrency);
        executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread thread = new Thread(r, "mcp-init");
            thread.setDaemon(true);
            return thread;
        });

        List<MCPServer> servers = new ArrayList<>();
        List<Callable<ServerInitResult>> tasks = new ArrayList<>();
        for (MCPServerConfig config : configs) {
            MCPServer server = createServer(config);
            servers.add(server);
            tasks.add(initTask(server));
        }

        int succeeded = 0;
        List<String> failures = new ArrayList<>();
        List<Future<ServerInitResult>> futures;
        long timeoutSeconds = maxInitTimeoutSeconds();
        try {
            futures = executor.invokeAll(tasks, timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.add("MCP 初始化被中断");
            return new InitSummary(0, configs.size(), List.copyOf(failures));
        }

        for (int i = 0; i < futures.size(); i++) {
            Future<ServerInitResult> future = futures.get(i);
            MCPServerConfig config = configs.get(i);
            MCPServer server = servers.get(i);
            try {
                if (future.isCancelled()) {
                    String failure = failureWithDiagnostics(config.name(),
                            "初始化超时 " + timeoutFor(config).toSeconds() + "s", server);
                    markFailed(config, server, failure);
                    failures.add(failure);
                    continue;
                }
                ServerInitResult result = future.get();
                if (result.errorMessage() != null) {
                    String failure = failureWithDiagnostics(config.name(), result.errorMessage(), server);
                    markFailed(config, server, failure);
                    failures.add(failure);
                    continue;
                }
                markRunning(config, result.server(), result.tools(), result.elapsedMillis());
                succeeded++;
                logger.accept("🔌 MCP server 已加载: " + config.name()
                        + " tools=" + result.tools().size()
                        + " initMs=" + result.elapsedMillis());
            } catch (CancellationException e) {
                server.close();
                String failure = failureWithDiagnostics(config.name(), "初始化已取消", server);
                markFailed(config, server, failure);
                failures.add(failure);
            } catch (ExecutionException e) {
                server.close();
                String failure = failureWithDiagnostics(config.name(), rootMessage(e), server);
                markFailed(config, server, failure);
                failures.add(failure);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.close();
                String failure = failureWithDiagnostics(config.name(), "初始化被中断", server);
                markFailed(config, server, failure);
                failures.add(failure);
            }
        }

        if (!failures.isEmpty()) {
            logger.accept("⚠ MCP 部分 server 初始化失败: " + failures);
            for (String failure : failures) {
                logger.accept("  - " + failure);
            }
        }
        logger.accept("🔌 MCP 初始化完成: success=" + succeeded + ", failed=" + failures.size());
        executor.shutdownNow();
        return new InitSummary(succeeded, failures.size(), List.copyOf(failures));
    }

    private Callable<ServerInitResult> initTask(MCPServer server) {
        return () -> {
            long startedNanos = System.nanoTime();
            try {
                List<ToolRegistry.Tool> tools = server.initializeAndCreateTools();
                return ServerInitResult.success(server, tools, elapsedMillis(startedNanos));
            } catch (Exception e) {
                server.close();
                return ServerInitResult.failure(server, e.getMessage(), elapsedMillis(startedNanos));
            }
        };
    }

    protected MCPServer createServer(MCPServerConfig config) {
        return new MCPServer(config);
    }

    public synchronized String statusReport() {
        if (runtimes.isEmpty()) {
            return "MCP servers: (none)\n";
        }
        StringBuilder sb = new StringBuilder("MCP servers:\n");
        for (ServerRuntime runtime : runtimes.values()) {
            sb.append("- ").append(runtime.config().name())
                    .append(" [").append(runtime.status()).append("]")
                    .append(runtime.enabled() ? "" : " disabled")
                    .append(" tools=").append(runtime.toolNames().size());
            if (runtime.lastInitMillis() > 0) {
                sb.append(" initMs=").append(runtime.lastInitMillis());
            }
            if (runtime.lastError() != null && !runtime.lastError().isBlank()) {
                sb.append(" lastError=").append(runtime.lastError());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public synchronized String restart(String name) {
        ServerRuntime runtime = runtimeFor(name);
        if (runtime == null) {
            return unknownServer(name);
        }
        if (!runtime.enabled()) {
            return "MCP server 已禁用: " + runtime.config().name() + "，请先使用 /mcp enable "
                    + runtime.config().name() + "\n";
        }
        stopRuntime(runtime, ServerStatus.STOPPED, "restart requested");
        return startOne(runtime.config(), "重启");
    }

    public synchronized String disable(String name) {
        ServerRuntime runtime = runtimeFor(name);
        if (runtime == null) {
            return unknownServer(name);
        }
        stopRuntime(runtime, ServerStatus.DISABLED, "disabled by user");
        runtime.enabled(false);
        return "MCP server 已禁用: " + runtime.config().name() + "\n";
    }

    public synchronized String enable(String name) {
        ServerRuntime runtime = runtimeFor(name);
        if (runtime == null) {
            return unknownServer(name);
        }
        runtime.enabled(true);
        if (runtime.status() == ServerStatus.RUNNING) {
            return "MCP server 已经运行: " + runtime.config().name() + "\n";
        }
        return startOne(runtime.config(), "启用");
    }

    public synchronized String logs(String name) {
        ServerRuntime runtime = runtimeFor(name);
        if (runtime == null) {
            return unknownServer(name);
        }
        String logs = runtime.server() == null ? runtime.logs() : runtime.server().logs();
        if (logs == null || logs.isBlank()) {
            logs = "(empty)";
        }
        return "MCP logs [" + runtime.config().name() + "]:\n" + logs + "\n";
    }

    private String startOne(MCPServerConfig config, String action) {
        MCPServer server = createServer(config);
        ExecutorService single = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mcp-" + action + "-" + config.name());
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<ServerInitResult> future = single.submit(initTask(server));
            ServerInitResult result = future.get(timeoutFor(config).toSeconds(), TimeUnit.SECONDS);
            if (result.errorMessage() != null) {
                String failure = failureWithDiagnostics(config.name(), result.errorMessage(), server);
                markFailed(config, server, failure);
                return "❌ MCP server " + action + "失败: " + failure + "\n";
            }
            markRunning(config, result.server(), result.tools(), result.elapsedMillis());
            return "✅ MCP server " + action + "成功: " + config.name()
                    + " tools=" + result.tools().size()
                    + " initMs=" + result.elapsedMillis() + "\n";
        } catch (java.util.concurrent.TimeoutException e) {
            futureCancel(server);
            String failure = failureWithDiagnostics(config.name(), action + "超时 " + timeoutFor(config).toSeconds() + "s", server);
            markFailed(config, server, failure);
            return "❌ MCP server " + action + "失败: " + failure + "\n";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futureCancel(server);
            String failure = failureWithDiagnostics(config.name(), action + "被中断", server);
            markFailed(config, server, failure);
            return "❌ MCP server " + action + "失败: " + failure + "\n";
        } catch (ExecutionException e) {
            futureCancel(server);
            String failure = failureWithDiagnostics(config.name(), rootMessage(e), server);
            markFailed(config, server, failure);
            return "❌ MCP server " + action + "失败: " + failure + "\n";
        } finally {
            single.shutdownNow();
        }
    }

    private void futureCancel(MCPServer server) {
        if (server != null) {
            server.close();
        }
    }

    private void markRunning(MCPServerConfig config, MCPServer server, List<ToolRegistry.Tool> tools, long elapsedMillis) {
        ServerRuntime runtime = runtimes.computeIfAbsent(config.name(), ignored -> ServerRuntime.initial(config));
        unregisterTools(runtime);
        List<String> toolNames = new ArrayList<>();
        for (ToolRegistry.Tool tool : tools == null ? List.<ToolRegistry.Tool>of() : tools) {
            toolRegistry.registerTool(tool);
            toolNames.add(tool.name());
        }
        runtime.server(server);
        runtime.toolNames(List.copyOf(toolNames));
        runtime.status(ServerStatus.RUNNING);
        runtime.enabled(true);
        runtime.lastError(null);
        runtime.diagnostics(server == null ? "" : server.diagnostics());
        runtime.logs(server == null ? "" : server.logs());
        runtime.lastInitMillis(elapsedMillis);
    }

    private void markFailed(MCPServerConfig config, MCPServer server, String failure) {
        ServerRuntime runtime = runtimes.computeIfAbsent(config.name(), ignored -> ServerRuntime.initial(config));
        unregisterTools(runtime);
        runtime.server(null);
        runtime.toolNames(List.of());
        runtime.status(runtime.enabled() ? ServerStatus.FAILED : ServerStatus.DISABLED);
        runtime.lastError(failure);
        runtime.diagnostics(server == null ? "" : server.diagnostics());
        runtime.logs(server == null ? "" : server.logs());
        if (server != null) {
            server.close();
        }
    }

    private void stopRuntime(ServerRuntime runtime, ServerStatus status, String reason) {
        unregisterTools(runtime);
        if (runtime.server() != null) {
            runtime.diagnostics(runtime.server().diagnostics());
            runtime.logs(runtime.server().logs());
            runtime.server().close();
        }
        runtime.server(null);
        runtime.toolNames(List.of());
        runtime.status(status);
        runtime.lastError(reason);
    }

    private void unregisterTools(ServerRuntime runtime) {
        for (String toolName : runtime.toolNames()) {
            toolRegistry.unregisterTool(toolName);
        }
    }

    private ServerRuntime runtimeFor(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return runtimes.get(name.trim());
    }

    private String unknownServer(String name) {
        return "❌ 未找到 MCP server: " + (name == null || name.isBlank() ? "(empty)" : name)
                + "\n可用 server: " + String.join(", ", configByName.keySet()) + "\n";
    }

    private long maxInitTimeoutSeconds() {
        long max = initTimeout.toSeconds();
        for (MCPServerConfig config : configs) {
            max = Math.max(max, timeoutFor(config).toSeconds());
        }
        return max;
    }

    private Duration timeoutFor(MCPServerConfig config) {
        Integer override = config == null ? null : config.initTimeoutSeconds();
        if (override != null && override > 0) {
            return Duration.ofSeconds(override);
        }
        return initTimeout;
    }

    private static int readIntProperty(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String rootMessage(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String failureWithDiagnostics(String serverName, String message, MCPServer server) {
        String diagnostics = server == null ? "" : server.diagnostics();
        String normalizedMessage = message == null || message.isBlank() ? "unknown error" : message;
        if (diagnostics == null || diagnostics.isBlank()) {
            return serverName + ": " + normalizedMessage;
        }
        return serverName + ": " + normalizedMessage + " | " + diagnostics;
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
        for (ServerRuntime runtime : runtimes.values()) {
            if (runtime.server() != null) {
                runtime.server().close();
                runtime.server(null);
                runtime.status(runtime.enabled() ? ServerStatus.STOPPED : ServerStatus.DISABLED);
            }
        }
    }

    public record InitSummary(int succeeded, int failed, List<String> failures) {}

    private record ServerInitResult(MCPServer server, List<ToolRegistry.Tool> tools, String errorMessage, long elapsedMillis) {
        static ServerInitResult success(MCPServer server, List<ToolRegistry.Tool> tools, long elapsedMillis) {
            return new ServerInitResult(server, tools == null ? List.of() : List.copyOf(tools), null, elapsedMillis);
        }

        static ServerInitResult failure(MCPServer server, String errorMessage, long elapsedMillis) {
            return new ServerInitResult(server, List.of(), errorMessage == null ? "unknown error" : errorMessage, elapsedMillis);
        }
    }

    public enum ServerStatus {
        STOPPED,
        RUNNING,
        FAILED,
        DISABLED
    }

    private static final class ServerRuntime {
        private final MCPServerConfig config;
        private boolean enabled = true;
        private ServerStatus status = ServerStatus.STOPPED;
        private MCPServer server;
        private List<String> toolNames = List.of();
        private String lastError;
        private String diagnostics = "";
        private String logs = "";
        private long lastInitMillis;

        private ServerRuntime(MCPServerConfig config) {
            this.config = config;
        }

        static ServerRuntime initial(MCPServerConfig config) {
            return new ServerRuntime(config);
        }

        MCPServerConfig config() {
            return config;
        }

        boolean enabled() {
            return enabled;
        }

        void enabled(boolean enabled) {
            this.enabled = enabled;
        }

        ServerStatus status() {
            return status;
        }

        void status(ServerStatus status) {
            this.status = status;
        }

        MCPServer server() {
            return server;
        }

        void server(MCPServer server) {
            this.server = server;
        }

        List<String> toolNames() {
            return toolNames;
        }

        void toolNames(List<String> toolNames) {
            this.toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        }

        String lastError() {
            return lastError;
        }

        void lastError(String lastError) {
            this.lastError = lastError;
        }

        String diagnostics() {
            return diagnostics;
        }

        void diagnostics(String diagnostics) {
            this.diagnostics = diagnostics == null ? "" : diagnostics;
        }

        String logs() {
            return logs;
        }

        void logs(String logs) {
            this.logs = logs == null ? "" : logs;
        }

        long lastInitMillis() {
            return lastInitMillis;
        }

        void lastInitMillis(long lastInitMillis) {
            this.lastInitMillis = Math.max(0, lastInitMillis);
        }
    }
}
