package dev.hotreload.integration;

import dev.hotreload.protocol.io.FrameCodec;
import dev.hotreload.protocol.message.ClassReloadRequest;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.HelloRequest;
import dev.hotreload.protocol.message.HelloResponse;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.session.SessionDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRD scenario #1 end to end: add a controller method on a real Spring Boot 2.7 app
 * running on JDK 8 + DCEVM (enhanced redefine, E2 engine) and verify routes over HTTP.
 */
class SpringMvcAgentJvmIntegrationTest {
    private static final String MAIN_CLASS = "dev.hotreload.integration.boot2.Boot2WebApplication";
    private static final String CONTROLLER = "dev.hotreload.integration.boot2.DemoController";
    private static final String SERVICE = "dev.hotreload.integration.boot2.DemoService";

    @TempDir Path temporaryDirectory;

    @Test
    void addControllerMethodOnEnhancedRuntimeKeepsAllRoutes() throws Exception {
        Path jdk8 = requiredPath("hotreload.jdk8.home");
        Path launchDirectory = Files.createDirectory(temporaryDirectory.resolve("boot2-dcevm"));
        Path sessionPath = launchDirectory.resolve("session.properties");
        Path logPattern = launchDirectory.resolve("agent-%g.log");
        String launchId = UUID.randomUUID().toString();
        String token = randomToken();

        try (JvmFixtureProcess fixture = JvmFixtureProcess.start(jdk8,
                requiredPath("hotreload.agent.jar"), requiredText("hotreload.spring.fixture.classpath"),
                sessionPath, logPattern, token, launchId, MAIN_CLASS,
                Arrays.asList("-XXaltjvm=dcevm", "-XX:TieredStopAtLevel=1",
                        "-Dcom.sun.management.jmxremote"))) {
            fixture.awaitReady();
            int port = Integer.parseInt(fixture.command("PORT"));
            waitFor("session descriptor", () -> Files.isRegularFile(sessionPath));
            SessionDescriptor descriptor = SessionDescriptor.read(sessionPath);

            assertEquals("A1SVC", httpGet(port, "/demo/a"));
            assertEquals("ID7", httpGet(port, "/demo/7"));
            // Cold-start semantics: /demo/c has no dedicated route yet, so the /{id} template
            // captures it and Long conversion fails -> 400 (NOT 404). This is Spring's own
            // routing behavior for controllers that declare a path-variable catch-all.
            int coldStatus = httpStatus(port, "/demo/c");

            try (Socket socket = new Socket(descriptor.getAddress(), descriptor.getPort())) {
                socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(15));
                try {
                FrameCodec.write(socket.getOutputStream(), new HelloRequest("hello", token, launchId));
                HelloResponse hello = (HelloResponse) FrameCodec.read(socket.getInputStream());
                assertTrue(hello.isEnhancedRedefineSupported(),
                        "DCEVM must be detected as the enhanced engine");

                byte[] v2 = Files.readAllBytes(requiredPath("hotreload.spring.reload.classes")
                        .resolve(CONTROLLER.replace('.', java.io.File.separatorChar) + ".class"));
                byte[] v1 = Files.readAllBytes(requiredPath("hotreload.spring.main.classes")
                        .resolve(CONTROLLER.replace('.', java.io.File.separatorChar) + ".class"));
                byte[] v3 = Files.readAllBytes(requiredPath("hotreload.spring.reload2.classes")
                        .resolve(CONTROLLER.replace('.', java.io.File.separatorChar) + ".class"));

                // --- Scenario 1: ANNOTATION-ONLY change (user's exact case, in-place path) ---
                reload(socket, token, "ann-add", v3);
                try {
                    assertEquals("TAGGED:A1SVC", httpGet(port, "/demo/a"));
                } catch (Throwable failure) {
                    throw new AssertionError("annotation-ADD (in-place) failed\n--- fixture ---\n"
                            + fixture.recentOutputText() + "\n--- agent log ---\n"
                            + agentLogs(launchDirectory), failure);
                }
                reload(socket, token, "ann-remove", v1);
                try {
                    assertEquals("A1SVC", httpGet(port, "/demo/a"));
                } catch (Throwable failure) {
                    throw new AssertionError("annotation-REMOVE (in-place) failed\n--- fixture ---\n"
                            + fixture.recentOutputText() + "\n--- agent log ---\n"
                            + agentLogs(launchDirectory), failure);
                }

                // --- Scenario 1b: CUSTOM annotation introduced on an UN-PROXIED bean ---
                // DemoService has no aspect yet; @Tagged must trigger generic advisor
                // recomputation -> proxy woven -> aspect intercepts (no hardcoded names).
                byte[] serviceV3 = Files.readAllBytes(requiredPath("hotreload.spring.reload2.classes")
                        .resolve(SERVICE.replace('.', java.io.File.separatorChar) + ".class"));
                byte[] serviceV1 = Files.readAllBytes(requiredPath("hotreload.spring.main.classes")
                        .resolve(SERVICE.replace('.', java.io.File.separatorChar) + ".class"));
                reloadClass(socket, token, "svc-ann-add", SERVICE, serviceV3);
                try {
                    assertEquals("A1TAGGED:SVC", httpGet(port, "/demo/a"));
                } catch (Throwable failure) {
                    throw new AssertionError("custom-annotation proxy introduction failed\n--- fixture ---\n"
                            + fixture.recentOutputText() + "\n--- agent log ---\n"
                            + agentLogs(launchDirectory), failure);
                }
                reloadClass(socket, token, "svc-ann-remove", SERVICE, serviceV1);
                try {
                    assertEquals("A1SVC", httpGet(port, "/demo/a"));
                } catch (Throwable failure) {
                    throw new AssertionError("custom-annotation removal failed\n--- fixture ---\n"
                            + fixture.recentOutputText() + "\n--- agent log ---\n"
                            + agentLogs(launchDirectory), failure);
                }

                // --- Scenario 2: structural change (new method) + annotation ---
                reload(socket, token, "class", v2);
                try {
                    assertEquals("C1SVC", httpGet(port, "/demo/c"));
                    assertEquals("TAGGED:A2SVC", httpGet(port, "/demo/a"));
                    assertEquals("ID7", httpGet(port, "/demo/7"));
                } catch (Throwable failure) {
                    throw new AssertionError("route verification failed\n--- fixture ---\n"
                            + fixture.recentOutputText() + "\n--- agent log ---\n"
                            + agentLogs(launchDirectory), failure);
                }

                // --- Scenario 3: roll back to v1: method + annotation removed ---
                reload(socket, token, "rollback", v1);
                try {
                    assertEquals("A1SVC", httpGet(port, "/demo/a"));
                    // After deleting the method, /demo/c must behave EXACTLY like cold start:
                    // captured by the /{id} template route (same status), not our concern.
                    assertEquals(coldStatus, httpStatus(port, "/demo/c"),
                            "deleted-route semantics must match a never-reloaded app");
                } catch (Throwable failure) {
                    throw new AssertionError("rollback verification failed\n--- fixture ---\n"
                            + fixture.recentOutputText() + "\n--- agent log ---\n"
                            + agentLogs(launchDirectory), failure);
                }
                } catch (java.io.IOException transport) {
                    throw new AssertionError("agent transport died\n--- fixture ---\n"
                            + fixture.recentOutputText() + "\n--- agent log ---\n"
                            + agentLogs(launchDirectory), transport);
                }
            }

            fixture.stopGracefully();
        }
    }

    private static void reload(Socket socket, String token, String requestId, byte[] bytecode)
            throws Exception {
        reloadClass(socket, token, requestId, CONTROLLER, bytecode);
    }

    private static void reloadClass(Socket socket, String token, String requestId,
                                    String className, byte[] bytecode) throws Exception {
        FrameCodec.write(socket.getOutputStream(), new ClassReloadRequest(requestId, token,
                Collections.singletonList(new ClassUpdate(className, bytecode))));
        ReloadResponse response = (ReloadResponse) FrameCodec.read(socket.getInputStream());
        assertEquals(OperationStatus.SUCCESS, response.getStatus(),
                requestId + " failed: " + response.getMessage());
    }

    private static int httpStatus(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream != null) readAll(stream);
        return status;
    }

    private static String httpGet(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : readAll(stream);
        if (status != 200) {
            throw new AssertionError("GET " + path + " -> " + status + " body=" + body);
        }
        return body;
    }

    private static String readAll(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
        stream.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String agentLogs(Path directory) {
        StringBuilder logs = new StringBuilder();
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths
                    .filter(candidate -> candidate.getFileName().toString().startsWith("agent-"))
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".log"))::iterator) {
                logs.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            logs.append("log read failed: ").append(e);
        }
        return logs.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void waitFor(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean(), description + " did not complete");
    }

    private static Path requiredPath(String property) {
        return Paths.get(requiredText(property)).toAbsolutePath().normalize();
    }

    private static String requiredText(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException("Missing " + property);
        return value;
    }
}
