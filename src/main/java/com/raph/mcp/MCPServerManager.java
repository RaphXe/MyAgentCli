package com.raph.mcp;

import com.raph.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    private final List<MCPServer> runningServers = new ArrayList<>();
    private ExecutorService executor;

    public MCPServerManager(List<MCPServerConfig> configs, ToolRegistry toolRegistry, Consumer<String> logger) {
        this.configs = configs == null ? List.of() : List.copyOf(configs);
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.logger = logger == null ? ignored -> {} : logger;
        this.initConcurrency = Math.max(1, readIntProperty("paicli.mcp.init.concurrency", DEFAULT_INIT_CONCURRENCY));
        this.initTimeout = Duration.ofSeconds(Math.max(1,
                readIntProperty("paicli.mcp.init.timeout.seconds", DEFAULT_INIT_TIMEOUT_SECONDS)));
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
                    server.close();
                    failures.add(failureWithDiagnostics(config.name(),
                            "初始化超时 " + timeoutFor(config).toSeconds() + "s", server));
                    continue;
                }
                ServerInitResult result = future.get();
                if (result.errorMessage() != null) {
                    failures.add(failureWithDiagnostics(config.name(), result.errorMessage(), server));
                    continue;
                }
                runningServers.add(result.server());
                for (ToolRegistry.Tool tool : result.tools()) {
                    toolRegistry.registerTool(tool);
                }
                succeeded++;
                logger.accept("🔌 MCP server 已加载: " + config.name() + " tools=" + result.tools().size());
            } catch (CancellationException e) {
                server.close();
                failures.add(failureWithDiagnostics(config.name(), "初始化已取消", server));
            } catch (ExecutionException e) {
                server.close();
                failures.add(failureWithDiagnostics(config.name(), rootMessage(e), server));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.close();
                failures.add(failureWithDiagnostics(config.name(), "初始化被中断", server));
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
            try {
                List<ToolRegistry.Tool> tools = server.initializeAndCreateTools();
                return ServerInitResult.success(server, tools);
            } catch (Exception e) {
                server.close();
                return ServerInitResult.failure(server, e.getMessage());
            }
        };
    }

    protected MCPServer createServer(MCPServerConfig config) {
        return new MCPServer(config);
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
        for (MCPServer server : runningServers) {
            server.close();
        }
        runningServers.clear();
    }

    public record InitSummary(int succeeded, int failed, List<String> failures) {}

    private record ServerInitResult(MCPServer server, List<ToolRegistry.Tool> tools, String errorMessage) {
        static ServerInitResult success(MCPServer server, List<ToolRegistry.Tool> tools) {
            return new ServerInitResult(server, tools == null ? List.of() : List.copyOf(tools), null);
        }

        static ServerInitResult failure(MCPServer server, String errorMessage) {
            return new ServerInitResult(server, List.of(), errorMessage == null ? "unknown error" : errorMessage);
        }
    }
}
