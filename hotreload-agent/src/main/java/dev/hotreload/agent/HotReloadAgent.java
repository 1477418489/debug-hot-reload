package dev.hotreload.agent;

import dev.hotreload.agent.config.AgentOptions;
import dev.hotreload.agent.lifecycle.AgentLifecycle;
import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.agent.instrument.MyBatisInstrumentation;
import dev.hotreload.agent.spring.SpringContextInstrumentation;
import dev.hotreload.agent.spring.SpringAnnotationInstrumentation;
import dev.hotreload.agent.server.AgentServer;
import dev.hotreload.agent.server.RequestDispatcher;
import dev.hotreload.bootstrap.HotReloadBridge;
import dev.hotreload.agent.classes.EngineCapabilityProbe;
import dev.hotreload.agent.classes.HotReloadClassRegistry;
import dev.hotreload.agent.compat.RuntimeEnvironment;
import dev.hotreload.agent.compat.RuntimeEnvironmentProbe;
import dev.hotreload.agent.classes.RuntimeAnnotationIndex;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class HotReloadAgent {
    private static final int BOOTSTRAP_API_VERSION = 1;
    private static final AtomicReference<RuntimeSession> ACTIVE = new AtomicReference<RuntimeSession>();
    private static final AgentSessionGuard SESSION_GUARD = new AgentSessionGuard();

    private HotReloadAgent() {
    }

    public static void premain(String rawOptions, Instrumentation instrumentation) {
        if (!SESSION_GUARD.tryAcquire()) {
            System.err.println("[idea-hotreload] Agent disabled: another session is active");
            return;
        }
        RuntimeSession runtime = null;
        AgentSessionLogger logger = null;
        BootstrapInstaller.Installation bootstrap = null;
        MyBatisInstrumentation myBatisInstrumentation = null;
        SpringContextInstrumentation springInstrumentation = null;
        SpringAnnotationInstrumentation springAnnotationInstrumentation = null;
        boolean runtimeOwnsGuard = false;
        boolean bridgeActivated = false;
        try {
            AgentOptions options = AgentOptions.parse(rawOptions);
            if (options.isVerboseLogs()) {
                System.setProperty("hotreload.annotation.verbose", "true");
            }
            logger = new AgentSessionLogger(options.getLaunchId(), options.getLogPath());
            RuntimeEnvironment environment = RuntimeEnvironmentProbe.probe(instrumentation);
            logger.log(Level.INFO, "AGENT_START", fields("javaVersion", System.getProperty("java.version"),
                    "redefineSupported", Boolean.toString(instrumentation.isRedefineClassesSupported()),
                    "detail", environment.summary()));
            logger.log(Level.INFO, "ENVIRONMENT_PROBE", environment.asLogFields());
            bootstrap = BootstrapInstaller.install(
                    instrumentation, options.getSessionPath().getParent());
            verifyBootstrapApi();
            HotReloadBridge.activate();
            bridgeActivated = true;
            logger.log(Level.INFO, "BOOTSTRAP_READY", fields("protocol",
                    Integer.toString(BOOTSTRAP_API_VERSION)));

            myBatisInstrumentation = MyBatisInstrumentation.install(instrumentation, logger);
            springInstrumentation = SpringContextInstrumentation.install(instrumentation, logger);
            springAnnotationInstrumentation = SpringAnnotationInstrumentation.install(instrumentation, logger);
            runtime = new RuntimeSession(options, instrumentation, logger, bootstrap, myBatisInstrumentation,
                    springInstrumentation, springAnnotationInstrumentation);
            if (!ACTIVE.compareAndSet(null, runtime)) {
                throw new IllegalStateException("A hot reload Agent session is already active");
            }
            runtimeOwnsGuard = true;
            runtime.start();
        } catch (Throwable failure) {
            if (logger != null) {
                logger.log(Level.SEVERE, "AGENT_START_FAILED", fields("reason",
                        failure.getClass().getSimpleName()));
            }
            if (runtime != null) runtime.close();
            else {
                if (springAnnotationInstrumentation != null) springAnnotationInstrumentation.close();
                if (springInstrumentation != null) springInstrumentation.close();
                if (myBatisInstrumentation != null) myBatisInstrumentation.close();
                if (bridgeActivated) HotReloadBridge.deactivate();
                HotReloadClassRegistry.clear();
                RuntimeAnnotationIndex.clearAll();
                if (bootstrap != null) {
                    try { bootstrap.close(); } catch (Exception ignored) { }
                }
                if (logger != null) logger.close();
            }
            System.err.println("[idea-hotreload] Agent disabled: " + failure.getClass().getSimpleName());
        } finally {
            if (!runtimeOwnsGuard) SESSION_GUARD.release();
        }
    }

    private static void verifyBootstrapApi() throws Exception {
        Class<?> bridge = Class.forName("dev.hotreload.bootstrap.HotReloadBridge", true, null);
        Method method = bridge.getMethod("apiVersion");
        int actual = ((Integer) method.invoke(null)).intValue();
        if (actual != BOOTSTRAP_API_VERSION) {
            throw new IllegalStateException("Unsupported bootstrap API version: " + actual);
        }
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) fields.put(keyValues[i], keyValues[i + 1]);
        return fields;
    }

    private static final class RuntimeSession implements AutoCloseable {
        private final AgentLifecycle lifecycle = new AgentLifecycle();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AgentServer server;
        private final AgentSessionLogger logger;
        private final Thread shutdownHook;

        private RuntimeSession(AgentOptions options, Instrumentation instrumentation,
                               AgentSessionLogger logger, BootstrapInstaller.Installation bootstrap,
                               MyBatisInstrumentation myBatisInstrumentation,
                               SpringContextInstrumentation springInstrumentation,
                               SpringAnnotationInstrumentation springAnnotationInstrumentation) {
            this.logger = logger;
            this.server = new AgentServer(options, logger, new RequestDispatcher(instrumentation, logger),
                    instrumentation.isRedefineClassesSupported(), AgentServer.DEFAULT_HELLO_TIMEOUT_MILLIS,
                    new Runnable() {
                        @Override public void run() { RuntimeSession.this.close(); }
                    });
            // Probe once: ENHANCED means structural redefine keeps class identity (E2 engine).
            this.server.setEnhancedRedefineSupported(
                    EngineCapabilityProbe.capability(instrumentation)
                            == EngineCapabilityProbe.Capability.ENHANCED);
            this.shutdownHook = new Thread(new Runnable() {
                @Override public void run() { RuntimeSession.this.close(); }
            }, "hotreload-shutdown");
            this.shutdownHook.setDaemon(true);
            lifecycle.register(bootstrap);
            lifecycle.register(new AutoCloseable() {
                @Override public void close() {
                    HotReloadBridge.deactivate();
                    HotReloadClassRegistry.clear();
                    RuntimeAnnotationIndex.clearAll();
                }
            });
            lifecycle.register(myBatisInstrumentation);
            lifecycle.register(springInstrumentation);
            lifecycle.register(springAnnotationInstrumentation);
            lifecycle.register(server);
        }

        private void start() throws Exception {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            server.start();
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                lifecycle.close();
                int closeFailures = lifecycle.getCloseFailureCount();
                try {
                    logger.log(closeFailures == 0 ? Level.INFO : Level.WARNING, "LIFECYCLE_CLEANUP",
                            fields("closeFailures", Integer.toString(closeFailures),
                                    "trackedConfigurations",
                                    Integer.toString(HotReloadBridge.snapshotConfigurations().size())));
                } finally {
                    logger.close();
                }
                if (Thread.currentThread() != shutdownHook) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    } catch (IllegalStateException ignored) {
                        // The JVM is already shutting down.
                    }
                }
            } finally {
                ACTIVE.compareAndSet(this, null);
                SESSION_GUARD.release();
            }
        }
    }

}
