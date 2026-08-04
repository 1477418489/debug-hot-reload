package dev.hotreload.agent.server;

import dev.hotreload.agent.config.AgentOptions;
import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.protocol.io.FrameCodec;
import dev.hotreload.protocol.message.ClassReloadRequest;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.HelloRequest;
import dev.hotreload.protocol.message.HelloResponse;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.message.ResourceReloadRequest;
import dev.hotreload.protocol.session.SessionDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentServerTest {
    @TempDir Path tempDirectory;

    @Test void invalidFirstSocketDoesNotPreventTheNextValidHello() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5000L);
        try {
            try (Socket invalid = new Socket("127.0.0.1", server.getPort())) {
                FrameCodec.write(invalid.getOutputStream(), new ClassReloadRequest("bad", fixture.token,
                        Collections.singletonList(new ClassUpdate("example.Type", new byte[]{1}))));
            }
            try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
                FrameCodec.write(valid.getOutputStream(), new HelloRequest("hello", fixture.token, fixture.launchId));
                Object response = FrameCodec.read(valid.getInputStream());
                assertEquals(new HelloResponse("hello", 1, true,
                        System.getProperty("java.version"), 0), response);
            }
        } finally {
            server.close();
        }
    }

    @Test void badTokenOnlyClosesTheAttempt() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5000L);
        try {
            try (Socket invalid = new Socket("127.0.0.1", server.getPort())) {
                FrameCodec.write(invalid.getOutputStream(), new HelloRequest("bad", "wrong", fixture.launchId));
            }
            try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
                FrameCodec.write(valid.getOutputStream(), new HelloRequest("hello", fixture.token, fixture.launchId));
                assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);
            }
        } finally {
            server.close();
        }
    }

    @Test void authenticatedEofClosesTheSessionAndRemovesDescriptor() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5000L);
        Socket valid = new Socket("127.0.0.1", server.getPort());
        FrameCodec.write(valid.getOutputStream(), new HelloRequest("hello", fixture.token, fixture.launchId));
        assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);
        valid.close();

        waitUntilClosed(server);
        assertFalse(Files.exists(fixture.sessionPath));
    }

    @Test void resourceRequestsReachTheRealDispatcherAndPreserveTheSession() throws Exception {
        Fixture fixture = new Fixture();
        AgentSessionLogger dispatcherLogger = new AgentSessionLogger(fixture.launchId,
                fixture.launchDirectory.resolve("dispatcher.log"));
        AgentServer server = fixture.start(5_000L,
                new RequestDispatcher(unsupportedInstrumentation(), dispatcherLogger));
        try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
            valid.setSoTimeout(2_000);
            FrameCodec.write(valid.getOutputStream(),
                    new HelloRequest("hello", fixture.token, fixture.launchId));
            assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);

            FrameCodec.write(valid.getOutputStream(), new ResourceReloadRequest(
                    "static", fixture.token, "static/app.css", new byte[]{1}, "css"));
            ReloadResponse staticResponse = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals("static", staticResponse.getRequestId());
            assertEquals(ReloadErrorCode.BRIDGE_UNAVAILABLE, staticResponse.getErrorCode());

            FrameCodec.write(valid.getOutputStream(), new ResourceReloadRequest(
                    "config", fixture.token, "application.properties",
                    "feature.enabled=true".getBytes(StandardCharsets.UTF_8), "properties"));
            ReloadResponse configResponse = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals("config", configResponse.getRequestId());
            assertEquals(ReloadErrorCode.BRIDGE_UNAVAILABLE, configResponse.getErrorCode());

            FrameCodec.write(valid.getOutputStream(), new ClassReloadRequest("class", fixture.token,
                    Collections.singletonList(new ClassUpdate("example.Type", new byte[]{1}))));
            ReloadResponse classResponse = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals("class", classResponse.getRequestId());
            assertEquals(ReloadErrorCode.CLASS_REDEFINE_UNSUPPORTED, classResponse.getErrorCode());
            assertFalse(server.isClosed());
        } finally {
            server.close();
            dispatcherLogger.close();
        }
    }

    @Test void oversizedResourceRequestReturnsAnErrorWithoutClosingTheSession() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5_000L, request -> new ReloadResponse(
                        request.getRequestId(),
                        dev.hotreload.protocol.message.OperationStatus.SUCCESS, null, "",
                        Collections.emptyList()),
                AgentServer.DEFAULT_FIRST_FRAME_TIMEOUT_MILLIS, 512L);
        try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
            valid.setSoTimeout(2_000);
            FrameCodec.write(valid.getOutputStream(),
                    new HelloRequest("hello", fixture.token, fixture.launchId));
            assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);

            FrameCodec.write(valid.getOutputStream(), new ResourceReloadRequest(
                    "resource", fixture.token, "static/app.css", new byte[1024], "css"));
            ReloadResponse rejected = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals("resource", rejected.getRequestId());
            assertEquals(dev.hotreload.protocol.message.ReloadErrorCode.PAYLOAD_TOO_LARGE,
                    rejected.getErrorCode());

            FrameCodec.write(valid.getOutputStream(), new ClassReloadRequest("class", fixture.token,
                    Collections.singletonList(new ClassUpdate("example.Type", new byte[]{1}))));
            ReloadResponse accepted = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals("class", accepted.getRequestId());
            assertFalse(server.isClosed());
        } finally {
            server.close();
        }
    }

    @Test void noHelloWithinDeadlineCleansTheSession() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(100L);
        waitUntilClosed(server);
        assertFalse(Files.exists(fixture.sessionPath));
    }

    @Test void startingAfterCloseCannotPublishAStaleDescriptor() throws Exception {
        Fixture fixture = new Fixture();
        AgentOptions options = AgentOptions.parse("session=" + encode(fixture.sessionPath)
                + ",log=" + encode(fixture.logPath) + ",token=" + fixture.token
                + ",launch=" + fixture.launchId);
        AgentSessionLogger logger = new AgentSessionLogger(fixture.launchId, fixture.logPath);
        AgentServer server = new AgentServer(options, logger, request -> new ReloadResponse("request",
                dev.hotreload.protocol.message.OperationStatus.SUCCESS, null, "", Collections.emptyList()), true);

        server.close();

        assertThrows(IllegalStateException.class, server::start);
        assertFalse(Files.exists(fixture.sessionPath));
    }

    @Test void closingServerClosesAStalledHelloSocket() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5000L);
        try (Socket stalled = new Socket("127.0.0.1", server.getPort())) {
            stalled.setSoTimeout(2000);
            waitUntil(() -> hasThread("hotreload-client-"));

            server.close();

            assertEquals(-1, stalled.getInputStream().read());
            waitUntil(() -> !hasThread("hotreload-client-"));
        }
    }

    @Test void closingReleasesSlotsOwnedByQueuedHelloCandidates() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5_000L, request -> new ReloadResponse("request",
                dev.hotreload.protocol.message.OperationStatus.SUCCESS, null, "", Collections.emptyList()),
                5_000, 4 * 1024 * 1024);
        java.util.List<Socket> stalled = new java.util.ArrayList<Socket>();
        try {
            for (int i = 0; i < 8; i++) stalled.add(new Socket("127.0.0.1", server.getPort()));
            waitUntil(() -> unauthenticatedConnections(server) == 8);

            server.close();

            String logs = readLogs(fixture.launchDirectory);
            assertTrue(logs.matches("(?s).*event=RESOURCE_SNAPSHOT[^\\r\\n]*"
                    + "unauthenticatedConnections=0.*"), logs);
        } finally {
            for (Socket socket : stalled) socket.close();
            server.close();
        }
    }

    @Test void silentCandidatesExpireWithoutHoldingTheSessionDeadline() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5_000L, request -> new ReloadResponse("request",
                dev.hotreload.protocol.message.OperationStatus.SUCCESS, null, "", Collections.emptyList()),
                75, 4 * 1024 * 1024);
        java.util.List<Socket> stalled = new java.util.ArrayList<Socket>();
        try {
            for (int i = 0; i < 8; i++) stalled.add(new Socket("127.0.0.1", server.getPort()));
            Thread.sleep(500L);
            try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
                valid.setSoTimeout(2_000);
                FrameCodec.write(valid.getOutputStream(), new HelloRequest("hello", fixture.token, fixture.launchId));
                assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);
                assertFalse(server.isClosed());
            }
        } finally {
            for (Socket socket : stalled) socket.close();
            server.close();
        }
    }

    @Test void rejectsQueuedPayloadsWhenTheTotalByteBudgetIsReserved() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        ClassReloadRequest first = new ClassReloadRequest("first", fixture.token,
                Collections.singletonList(new ClassUpdate("example.FirstLongName", new byte[]{1, 2, 3, 4})));
        ClassReloadRequest second = new ClassReloadRequest("second", fixture.token,
                Collections.singletonList(new ClassUpdate("example.B", new byte[]{1})));
        long frameBudget = FrameCodec.encode(first).length - Integer.BYTES;
        AgentServer server = fixture.start(5_000L, request -> {
            mutationStarted.countDown();
            try {
                releaseMutation.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ReloadResponse("first", dev.hotreload.protocol.message.OperationStatus.SUCCESS,
                    null, "", Collections.emptyList());
        }, 1_000, frameBudget);
        try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
            valid.setSoTimeout(2_000);
            FrameCodec.write(valid.getOutputStream(), new HelloRequest("hello", fixture.token, fixture.launchId));
            assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);
            FrameCodec.write(valid.getOutputStream(), first);
            assertTrue(mutationStarted.await(1, TimeUnit.SECONDS));
            FrameCodec.write(valid.getOutputStream(), second);
            ReloadResponse rejected = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals("second", rejected.getRequestId());
            assertEquals(dev.hotreload.protocol.message.ReloadErrorCode.PAYLOAD_TOO_LARGE,
                    rejected.getErrorCode());
            releaseMutation.countDown();
            ReloadResponse accepted = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals("first", accepted.getRequestId());
        } finally {
            releaseMutation.countDown();
            server.close();
        }
    }

    @Test void reservesTheWholeRequestFrameBeforeDecodingItsPayload() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        ClassReloadRequest first = new ClassReloadRequest("first", fixture.token,
                Collections.singletonList(new ClassUpdate("example.FirstLongName", new byte[]{1})));
        long frameBytes = FrameCodec.encode(first).length - Integer.BYTES;
        AgentServer server = fixture.start(5_000L, request -> {
            mutationStarted.countDown();
            try {
                releaseMutation.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ReloadResponse("first", dev.hotreload.protocol.message.OperationStatus.SUCCESS,
                    null, "", Collections.emptyList());
        }, 1_000, frameBytes);
        try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
            valid.setSoTimeout(2_000);
            FrameCodec.write(valid.getOutputStream(), new HelloRequest("hello", fixture.token, fixture.launchId));
            assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);
            FrameCodec.write(valid.getOutputStream(), first);
            assertTrue(mutationStarted.await(1, TimeUnit.SECONDS));
            FrameCodec.write(valid.getOutputStream(), new ClassReloadRequest("second", fixture.token,
                    Collections.singletonList(new ClassUpdate("example.B", new byte[]{2}))));

            ReloadResponse rejected = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals("second", rejected.getRequestId());
            assertEquals(dev.hotreload.protocol.message.ReloadErrorCode.PAYLOAD_TOO_LARGE,
                    rejected.getErrorCode());
            releaseMutation.countDown();
            ReloadResponse accepted = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals("first", accepted.getRequestId());
        } finally {
            releaseMutation.countDown();
            server.close();
        }
    }

    @Test void returnsAnInternalErrorWhenMutationHandlerThrowsLinkageError() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5000L, ignoredRequest -> {
            throw new ExceptionInInitializerError("parser initialization failed");
        });
        try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
            valid.setSoTimeout(2000);
            FrameCodec.write(valid.getOutputStream(), new HelloRequest("hello", fixture.token, fixture.launchId));
            assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);
            FrameCodec.write(valid.getOutputStream(), new ClassReloadRequest("request", fixture.token,
                    Collections.singletonList(new ClassUpdate("example.Type", new byte[]{1}))));
            ReloadResponse response = (ReloadResponse) FrameCodec.read(valid.getInputStream());
            assertEquals(dev.hotreload.protocol.message.ReloadErrorCode.INTERNAL_ERROR, response.getErrorCode());
        } finally {
            server.close();
        }
    }

    @Test void logsFinalExecutorTerminationStateWhenClosedByOwner() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5000L);

        server.close();

        String logs = readLogs(fixture.launchDirectory);
        assertTrue(logs.contains("event=EXECUTOR_SHUTDOWN"), logs);
        assertTrue(logs.contains("acceptTerminated=true"), logs);
        assertTrue(logs.contains("clientTerminated=true"), logs);
        assertTrue(logs.contains("schedulerTerminated=true"), logs);
        assertTrue(logs.contains("mutationTerminated=true"), logs);
    }

    @Test void resourceSnapshotExplainsAuthenticatedSessionAndBoundedState() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5000L);
        try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
            FrameCodec.write(valid.getOutputStream(), new HelloRequest("hello", fixture.token, fixture.launchId));
            assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);

            server.close();

            String logs = readLogs(fixture.launchDirectory);
            assertTrue(logs.contains("event=RESOURCE_SNAPSHOT"), logs);
            assertTrue(logs.contains("activeSession=false"), logs);
            assertTrue(logs.contains("activeSessionBeforeClose=true"), logs);
            assertTrue(logs.contains("pendingPayloadBytes=0"), logs);
            assertTrue(logs.contains("trackedResources=0"), logs);
            assertTrue(logs.matches("(?s).*event=RESOURCE_SNAPSHOT[^\\r\\n]*recentEvents=\\d+.*"), logs);
            assertTrue(logs.contains("executorState=accept:TERMINATED,client:TERMINATED"), logs);
        }
    }

    @Test void resourceSnapshotCountsAnActiveMutationPayload() throws Exception {
        Fixture fixture = new Fixture();
        AtomicReference<AgentServer> activeServer = new AtomicReference<AgentServer>();
        ClassReloadRequest request = new ClassReloadRequest("request", fixture.token,
                Collections.singletonList(new ClassUpdate("example.Type", new byte[]{1, 2, 3})));
        long retainedFrameBytes = FrameCodec.encode(request).length - Integer.BYTES;
        AgentServer server = fixture.start(5000L, ignoredRequest -> {
            activeServer.get().close();
            return new ReloadResponse("request",
                    dev.hotreload.protocol.message.OperationStatus.SUCCESS, null, "",
                    Collections.emptyList());
        });
        activeServer.set(server);
        try (Socket valid = new Socket("127.0.0.1", server.getPort())) {
            FrameCodec.write(valid.getOutputStream(), new HelloRequest("hello", fixture.token, fixture.launchId));
            assertTrue(FrameCodec.read(valid.getInputStream()) instanceof HelloResponse);
            FrameCodec.write(valid.getOutputStream(), request);

            waitUntilClosed(server);

            String logs = readLogs(fixture.launchDirectory);
            assertTrue(logs.matches("(?s).*event=RESOURCE_SNAPSHOT[^\\r\\n]*pendingPayloadBytes="
                    + retainedFrameBytes + ".*"), logs);
        }
    }

    @Test void doesNotDeleteAReplacementSessionDescriptor() throws Exception {
        Fixture fixture = new Fixture();
        AgentServer server = fixture.start(5_000L);
        try {
            byte[] replacementKey = new byte[32];
            java.util.Arrays.fill(replacementKey, (byte) 9);
            SessionDescriptor.authenticated("replacement", 1, 1234, replacementKey)
                    .writeAtomically(fixture.sessionPath);

            server.close();

            assertTrue(Files.exists(fixture.sessionPath));
            assertEquals("replacement", SessionDescriptor.read(fixture.sessionPath).getLaunchId());
        } finally {
            server.close();
        }
    }

    private static void waitUntilClosed(AgentServer server) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!server.isClosed() && System.nanoTime() < deadline) Thread.sleep(10L);
        assertTrue(server.isClosed(), "server did not close before deadline");
    }

    private static void waitUntil(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!check.matches() && System.nanoTime() < deadline) Thread.sleep(10L);
        assertTrue(check.matches(), "condition did not complete");
    }

    private static boolean hasThread(String prefix) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(prefix)) return true;
        }
        return false;
    }

    private static int unauthenticatedConnections(AgentServer server) {
        try {
            java.lang.reflect.Field field = AgentServer.class.getDeclaredField("unauthenticatedConnectionCount");
            field.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicInteger) field.get(server)).get();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String readLogs(Path directory) throws Exception {
        StringBuilder result = new StringBuilder();
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths
                    .filter(candidate -> candidate.getFileName().toString().startsWith("agent.log"))::iterator) {
                result.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }

    private interface Check {
        boolean matches();
    }

    private final class Fixture {
        private final String launchId = UUID.randomUUID().toString();
        private final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        private final Path launchDirectory = tempDirectory.resolve("launch");
        private final Path sessionPath = launchDirectory.resolve("session.properties");
        private final Path logPath = launchDirectory.resolve("agent.log");

        private Fixture() throws Exception {
            Files.createDirectories(launchDirectory);
        }

        private AgentServer start(long timeoutMillis) throws Exception {
            return start(timeoutMillis, request ->
                    new ReloadResponse(request.getRequestId(),
                            dev.hotreload.protocol.message.OperationStatus.SUCCESS, null, "",
                            Collections.emptyList()));
        }

        private AgentServer start(long timeoutMillis, AgentServer.MutationHandler handler) throws Exception {
            return start(timeoutMillis, handler, AgentServer.DEFAULT_FIRST_FRAME_TIMEOUT_MILLIS,
                    AgentServer.DEFAULT_PENDING_PAYLOAD_LIMIT_BYTES);
        }

        private AgentServer start(long timeoutMillis, AgentServer.MutationHandler handler,
                                  int firstFrameTimeoutMillis, long pendingPayloadLimitBytes) throws Exception {
            AgentOptions options = AgentOptions.parse("session=" + encode(sessionPath) + ",log=" + encode(logPath)
                    + ",token=" + token + ",launch=" + launchId);
            AgentSessionLogger logger = new AgentSessionLogger(launchId, logPath);
            AgentServer server = new AgentServer(options, logger, handler, true, timeoutMillis,
                    firstFrameTimeoutMillis, pendingPayloadLimitBytes);
            server.start();
            assertTrue(Files.exists(sessionPath));
            return server;
        }
    }

    private static String encode(Path path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Instrumentation unsupportedInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(AgentServerTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, arguments) -> primitiveDefault(method.getReturnType()));
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return (char) 0;
        return null;
    }
}
