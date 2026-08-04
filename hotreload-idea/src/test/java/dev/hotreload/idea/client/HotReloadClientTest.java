package dev.hotreload.idea.client;

import dev.hotreload.protocol.io.FrameCodec;
import dev.hotreload.protocol.message.ClassReloadRequest;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.HelloRequest;
import dev.hotreload.protocol.message.HelloResponse;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.message.ResourceReloadRequest;
import dev.hotreload.protocol.session.SessionDescriptor;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HotReloadClientTest {
    @Test void authenticatesSendsOneClassBatchAndReleasesItsExecutor() throws Exception {
        String launchId = UUID.randomUUID().toString();
        String token = "token";
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ExecutorService serverThread = Executors.newSingleThreadExecutor();
        serverThread.execute(() -> serve(server, launchId, token));
        HotReloadClient client = new HotReloadClient(
                new SessionDescriptor(launchId, 1, server.getLocalPort()), token, launchId);
        try {
            HelloResponse hello = client.connect().get(2, TimeUnit.SECONDS);
            assertTrue(hello.isClassRedefineSupported());
            ReloadResponse response = client.reloadClasses(Collections.singletonList(
                    new ClassUpdate("demo.Type", new byte[]{1}))).get(2, TimeUnit.SECONDS);
            assertEquals(OperationStatus.SUCCESS, response.getStatus());
        } finally {
            client.close();
            server.close();
            serverThread.shutdownNow();
        }
        assertTrue(client.isClosed());
        assertEquals(0, client.getQueueSize());
    }

    @Test void rejectsAResponseForAnotherRequest() throws Exception {
        String launchId = UUID.randomUUID().toString();
        String token = "token";
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ExecutorService serverThread = Executors.newSingleThreadExecutor();
        serverThread.execute(() -> {
            try (Socket socket = server.accept()) {
                HelloRequest hello = (HelloRequest) FrameCodec.read(socket.getInputStream());
                FrameCodec.write(socket.getOutputStream(), new HelloResponse(hello.getRequestId(), 1, true, "21", 0));
                ClassReloadRequest request = (ClassReloadRequest) FrameCodec.read(socket.getInputStream());
                FrameCodec.write(socket.getOutputStream(), new ReloadResponse(UUID.randomUUID().toString(),
                        OperationStatus.SUCCESS, null, "", Collections.emptyList()));
                assertNotNull(request);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HotReloadClient client = new HotReloadClient(
                new SessionDescriptor(launchId, 1, server.getLocalPort()), token, launchId);
        try {
            client.connect().get(2, TimeUnit.SECONDS);
            assertThrows(Exception.class, () -> client.reloadClasses(Collections.singletonList(
                    new ClassUpdate("demo.Type", new byte[]{1}))).get(2, TimeUnit.SECONDS));
        } finally {
            client.close();
            server.close();
            serverThread.shutdownNow();
        }
    }

    @Test void sendsAnEmptyStaticResource() throws Exception {
        String launchId = UUID.randomUUID().toString();
        String token = "token";
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ExecutorService serverThread = Executors.newSingleThreadExecutor();
        serverThread.execute(() -> {
            try (Socket socket = server.accept()) {
                HelloRequest hello = (HelloRequest) FrameCodec.read(socket.getInputStream());
                FrameCodec.write(socket.getOutputStream(),
                        new HelloResponse(hello.getRequestId(), 1, true, "21", 0));
                ResourceReloadRequest request = (ResourceReloadRequest) FrameCodec.read(
                        socket.getInputStream());
                assertEquals("static/empty.css", request.getResourcePath());
                assertEquals(0, request.getContentLength());
                FrameCodec.write(socket.getOutputStream(), new ReloadResponse(request.getRequestId(),
                        OperationStatus.SUCCESS, null, "", Collections.emptyList()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HotReloadClient client = new HotReloadClient(
                new SessionDescriptor(launchId, 1, server.getLocalPort()), token, launchId);
        try {
            client.connect().get(2, TimeUnit.SECONDS);
            ReloadResponse response = client.reloadResource(
                    "static/empty.css", new byte[0], "css").get(2, TimeUnit.SECONDS);
            assertEquals(OperationStatus.SUCCESS, response.getStatus());
        } finally {
            client.close();
            server.close();
            serverThread.shutdownNow();
        }
    }

    private static void serve(ServerSocket server, String launchId, String token) {
        try (Socket socket = server.accept()) {
            HelloRequest hello = (HelloRequest) FrameCodec.read(socket.getInputStream());
            assertEquals(token, hello.getToken());
            assertEquals(launchId, hello.getLaunchId());
            FrameCodec.write(socket.getOutputStream(), new HelloResponse(hello.getRequestId(), 1, true, "21", 0));
            ClassReloadRequest request = (ClassReloadRequest) FrameCodec.read(socket.getInputStream());
            FrameCodec.write(socket.getOutputStream(), new ReloadResponse(request.getRequestId(),
                    OperationStatus.SUCCESS, null, "", Collections.emptyList()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
