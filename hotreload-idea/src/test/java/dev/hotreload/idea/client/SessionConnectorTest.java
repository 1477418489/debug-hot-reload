package dev.hotreload.idea.client;

import dev.hotreload.protocol.io.FrameCodec;
import dev.hotreload.protocol.message.HelloRequest;
import dev.hotreload.protocol.message.HelloResponse;
import dev.hotreload.protocol.session.SessionDescriptor;
import dev.hotreload.idea.run.AgentLaunchSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionConnectorTest {
    @TempDir Path tempDirectory;

    @Test void waitsForDescriptorThenAuthenticatesAndCanBeClosed() throws Exception {
        Path agent = tempDirectory.resolve("agent.jar");
        Files.write(agent, new byte[]{1});
        AgentLaunchSpec spec = AgentLaunchSpec.create(tempDirectory.resolve("launches"), agent);
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<HotReloadClient> authenticatedClient = new AtomicReference<>();
        try {
            scheduler.execute(() -> serveHello(server, spec));
            new SessionDescriptor(spec.getLaunchId(), 1, server.getLocalPort()).writeAtomically(spec.getSessionPath());
            SessionConnector connector = new SessionConnector(spec, scheduler, (event, launchId) -> { }, 10, 1_000);
            connector.start(new SessionConnector.Listener() {
                @Override public void connected(HotReloadClient client, HelloResponse response) {
                    authenticatedClient.set(client);
                    connected.countDown();
                }

                @Override public void deadlineExpired() {
                    fail("connector expired before Hello");
                }
            });
            assertTrue(connected.await(2, TimeUnit.SECONDS));
            connector.close();
            authenticatedClient.get().close();
            assertTrue(connector.isClosed());
        } finally {
            server.close();
            scheduler.shutdownNow();
        }
    }

    @Test void closingBeforeDescriptorDoesNotInvokeListener() throws Exception {
        Path agent = tempDirectory.resolve("agent.jar");
        Files.write(agent, new byte[]{1});
        AgentLaunchSpec spec = AgentLaunchSpec.create(tempDirectory.resolve("launches"), agent);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        CountDownLatch connected = new CountDownLatch(1);
        SessionConnector connector = new SessionConnector(spec, scheduler, (event, launchId) -> { }, 5, 500);
        try {
            connector.start(new SessionConnector.Listener() {
                @Override public void connected(HotReloadClient client, HelloResponse response) {
                    connected.countDown();
                }

                @Override public void deadlineExpired() {
                    fail("closed connector must not expire");
                }
            });
            connector.close();
            assertFalse(Files.exists(spec.getSessionPath()));
            assertFalse(connected.await(150, TimeUnit.MILLISECONDS));
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static void serveHello(ServerSocket server, AgentLaunchSpec spec) {
        try (Socket socket = server.accept()) {
            HelloRequest request = (HelloRequest) FrameCodec.read(socket.getInputStream());
            assertEquals(spec.getToken(), request.getToken());
            FrameCodec.write(socket.getOutputStream(), new HelloResponse(request.getRequestId(), 1, true, "21", 0));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
