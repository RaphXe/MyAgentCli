package com.raph.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raph.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCPServerManagerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearProperties() {
        System.clearProperty("paicli.mcp.init.concurrency");
        System.clearProperty("paicli.mcp.init.timeout.seconds");
    }

    @Test
    void initializesServersConcurrently() {
        System.setProperty("paicli.mcp.init.concurrency", "2");
        System.setProperty("paicli.mcp.init.timeout.seconds", "5");
        ToolRegistry registry = new ToolRegistry();
        List<String> logs = new ArrayList<>();
        MCPServerManager manager = new FakeManager(
                List.of(config("one"), config("two")),
                registry,
                logs::add,
                250,
                false
        );

        long started = System.nanoTime();
        MCPServerManager.InitSummary summary = manager.startAllAndRegisterTools();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(2, summary.succeeded());
        assertTrue(elapsedMillis < 450, "expected concurrent init, elapsed=" + elapsedMillis);
        assertTrue(registry.hasTool("mcp__one__echo"));
        assertTrue(registry.hasTool("mcp__two__echo"));
        assertTrue(manager.statusReport().contains("initMs="), manager.statusReport());
        assertTrue(String.join("\n", logs).contains("initMs="), logs.toString());
        manager.close();
    }

    @Test
    void failedServerDoesNotPreventSuccessfulServerRegistration() {
        System.setProperty("paicli.mcp.init.concurrency", "2");
        ToolRegistry registry = new ToolRegistry();
        MCPServerManager manager = new FakeManager(
                List.of(config("ok"), config("bad")),
                registry,
                ignored -> {},
                0,
                true
        );

        MCPServerManager.InitSummary summary = manager.startAllAndRegisterTools();

        assertEquals(1, summary.succeeded());
        assertEquals(1, summary.failed());
        assertTrue(registry.hasTool("mcp__ok__echo"));
        assertTrue(summary.failures().get(0).contains("bad"));
        manager.close();
    }

    @Test
    void closeClosesRunningServers() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger closed = new AtomicInteger();
        MCPServerManager manager = new FakeManager(
                List.of(config("one")),
                registry,
                ignored -> {},
                0,
                false,
                closed
        );

        manager.startAllAndRegisterTools();
        manager.close();

        assertEquals(1, closed.get());
    }

    @Test
    void timeoutFailureIncludesServerDiagnostics() {
        System.setProperty("paicli.mcp.init.timeout.seconds", "1");
        ToolRegistry registry = new ToolRegistry();
        List<String> logs = new ArrayList<>();
        MCPServerManager manager = new DiagnosticTimeoutManager(
                List.of(config("slow")),
                registry,
                logs::add
        );

        MCPServerManager.InitSummary summary = manager.startAllAndRegisterTools();

        assertEquals(0, summary.succeeded());
        assertEquals(1, summary.failed());
        assertTrue(summary.failures().get(0).contains("diagnostics=slow-server"), summary.failures().toString());
        assertTrue(String.join("\n", logs).contains("diagnostics=slow-server"), logs.toString());
        manager.close();
    }

    @Test
    void disableRemovesToolsAndEnableRegistersThemAgain() {
        ToolRegistry registry = new ToolRegistry();
        MCPServerManager manager = new FakeManager(
                List.of(config("one")),
                registry,
                ignored -> {},
                0,
                false
        );

        manager.startAllAndRegisterTools();
        assertTrue(registry.hasTool("mcp__one__echo"));

        String disabled = manager.disable("one");
        assertTrue(disabled.contains("已禁用"), disabled);
        assertTrue(!registry.hasTool("mcp__one__echo"));
        assertTrue(manager.statusReport().contains("DISABLED"));

        String enabled = manager.enable("one");
        assertTrue(enabled.contains("成功"), enabled);
        assertTrue(registry.hasTool("mcp__one__echo"));
        assertTrue(manager.statusReport().contains("RUNNING"));
        manager.close();
    }

    @Test
    void restartRemovesOldToolsAndRegistersFreshTools() {
        ToolRegistry registry = new ToolRegistry();
        MCPServerManager manager = new VersionedToolManager(
                List.of(config("one")),
                registry,
                ignored -> {}
        );

        manager.startAllAndRegisterTools();
        assertTrue(registry.hasTool("mcp__one__echo_1"));

        String restarted = manager.restart("one");
        assertTrue(restarted.contains("成功"), restarted);
        assertTrue(!registry.hasTool("mcp__one__echo_1"));
        assertTrue(registry.hasTool("mcp__one__echo_2"));
        manager.close();
    }

    private static MCPServerConfig config(String name) {
        return new MCPServerConfig(name, "stdio", "unused", List.of(), Map.of(), null, Map.of(), null);
    }

    private static final class FakeManager extends MCPServerManager {
        private final long sleepMillis;
        private final boolean failBad;
        private final AtomicInteger closed;

        private FakeManager(List<MCPServerConfig> configs, ToolRegistry registry,
                            java.util.function.Consumer<String> logger, long sleepMillis, boolean failBad) {
            this(configs, registry, logger, sleepMillis, failBad, new AtomicInteger());
        }

        private FakeManager(List<MCPServerConfig> configs, ToolRegistry registry,
                            java.util.function.Consumer<String> logger, long sleepMillis,
                            boolean failBad, AtomicInteger closed) {
            super(configs, registry, logger);
            this.sleepMillis = sleepMillis;
            this.failBad = failBad;
            this.closed = closed;
        }

        @Override
        protected MCPServer createServer(MCPServerConfig config) {
            return new FakeServer(config, sleepMillis, failBad, closed);
        }
    }

    private static final class FakeServer extends MCPServer {
        private final MCPServerConfig config;
        private final long sleepMillis;
        private final boolean failBad;
        private final AtomicInteger closed;

        private FakeServer(MCPServerConfig config, long sleepMillis, boolean failBad, AtomicInteger closed) {
            super(config);
            this.config = config;
            this.sleepMillis = sleepMillis;
            this.failBad = failBad;
            this.closed = closed;
        }

        @Override
        public List<ToolRegistry.Tool> initializeAndCreateTools() throws MCPException {
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MCPException("interrupted", e);
                }
            }
            if (failBad && "bad".equals(config.name())) {
                throw new MCPException("boom");
            }
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", MAPPER.createObjectNode());
            return List.of(new ToolRegistry.Tool(
                    "mcp__" + config.name() + "__echo",
                    "fake",
                    schema,
                    ToolRegistry.ToolMetadata.readOnly(),
                    args -> "ok"
            ));
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }

    private static final class DiagnosticTimeoutManager extends MCPServerManager {
        private DiagnosticTimeoutManager(List<MCPServerConfig> configs, ToolRegistry registry,
                                         java.util.function.Consumer<String> logger) {
            super(configs, registry, logger);
        }

        @Override
        protected MCPServer createServer(MCPServerConfig config) {
            return new DiagnosticSlowServer(config);
        }
    }

    private static final class DiagnosticSlowServer extends MCPServer {
        private DiagnosticSlowServer(MCPServerConfig config) {
            super(config);
        }

        @Override
        public List<ToolRegistry.Tool> initializeAndCreateTools() throws MCPException {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MCPException("interrupted", e);
            }
            return List.of();
        }

        @Override
        public String diagnostics() {
            return "diagnostics=slow-server";
        }
    }

    private static final class VersionedToolManager extends MCPServerManager {
        private final AtomicInteger version = new AtomicInteger();

        private VersionedToolManager(List<MCPServerConfig> configs, ToolRegistry registry,
                                     java.util.function.Consumer<String> logger) {
            super(configs, registry, logger);
        }

        @Override
        protected MCPServer createServer(MCPServerConfig config) {
            return new VersionedToolServer(config, version.incrementAndGet());
        }
    }

    private static final class VersionedToolServer extends MCPServer {
        private final MCPServerConfig config;
        private final int version;

        private VersionedToolServer(MCPServerConfig config, int version) {
            super(config);
            this.config = config;
            this.version = version;
        }

        @Override
        public List<ToolRegistry.Tool> initializeAndCreateTools() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", MAPPER.createObjectNode());
            return List.of(new ToolRegistry.Tool(
                    "mcp__" + config.name() + "__echo_" + version,
                    "fake",
                    schema,
                    ToolRegistry.ToolMetadata.readOnly(),
                    args -> "ok"
            ));
        }
    }
}
