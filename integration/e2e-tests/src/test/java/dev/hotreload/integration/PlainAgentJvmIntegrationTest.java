package dev.hotreload.integration;

import dev.hotreload.protocol.io.FrameCodec;
import dev.hotreload.protocol.message.ClassReloadRequest;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.HelloRequest;
import dev.hotreload.protocol.message.HelloResponse;
import dev.hotreload.protocol.message.MapperReloadRequest;
import dev.hotreload.protocol.message.MapperUpdate;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.session.SessionDescriptor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainAgentJvmIntegrationTest {
    private static final String RESOURCE_ID = "mappers/ProbeMapper.xml";
    private static final String RELOADABLE_CLASS = "dev.hotreload.integration.plain.ReloadableService";

    @TempDir Path temporaryDirectory;

    @TestFactory Collection<DynamicTest> reloadsXmlAndClassOnSupportedJdks() {
        List<DynamicTest> tests = new ArrayList<DynamicTest>();
        for (String version : Arrays.asList("8", "11", "21")) {
            Path javaHome = requiredPath("hotreload.jdk" + version + ".home");
            tests.add(DynamicTest.dynamicTest("JDK " + version, () -> runReloadFlow(version, javaHome)));
        }
        return tests;
    }

    @TestFactory Collection<DynamicTest> cleansResourcesWhenTargetJvmStops() {
        List<DynamicTest> tests = new ArrayList<DynamicTest>();
        for (String version : Arrays.asList("8", "11", "21")) {
            Path javaHome = requiredPath("hotreload.jdk" + version + ".home");
            tests.add(DynamicTest.dynamicTest("JDK " + version, () -> runProcessStopFlow(version, javaHome)));
        }
        return tests;
    }

    private void runReloadFlow(String version, Path javaHome) throws Exception {
        Path launchDirectory = Files.createDirectory(temporaryDirectory.resolve("eof-jdk-" + version));
        Path sessionPath = launchDirectory.resolve("session.properties");
        Path logPattern = launchDirectory.resolve("agent-%g.log");
        String launchId = UUID.randomUUID().toString();
        String token = randomToken();

        try (JvmFixtureProcess fixture = JvmFixtureProcess.start(javaHome,
                requiredPath("hotreload.agent.jar"), requiredText("hotreload.fixture.classpath"),
                sessionPath, logPattern, token, launchId)) {
            fixture.awaitReady();
            waitFor("session descriptor", () -> Files.isRegularFile(sessionPath));
            SessionDescriptor descriptor = SessionDescriptor.read(sessionPath);

            try (Socket socket = new Socket(descriptor.getAddress(), descriptor.getPort())) {
                socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(10));
                FrameCodec.write(socket.getOutputStream(), new HelloRequest("hello", token, launchId));
                HelloResponse hello = (HelloResponse) FrameCodec.read(socket.getInputStream());
                assertEquals("hello", hello.getRequestId());
                assertEquals(1, hello.getProtocolVersion());
                assertTrue(hello.isClassRedefineSupported());
                assertEquals(1, hello.getConfigurationCount());

                assertEquals("V1", fixture.command("CLASS"));
                assertTrue(fixture.command("XML").contains("V1"));

                byte[] xml = mapperXml("V2");
                FrameCodec.write(socket.getOutputStream(), new MapperReloadRequest("xml", token,
                        new MapperUpdate(RESOURCE_ID, sha256(xml), xml)));
                ReloadResponse xmlResponse = (ReloadResponse) FrameCodec.read(socket.getInputStream());
                assertEquals("xml", xmlResponse.getRequestId());
                assertEquals(OperationStatus.SUCCESS, xmlResponse.getStatus());
                assertTrue(fixture.command("XML").contains("V2"));

                byte[] replacement = Files.readAllBytes(replacementClassPath());
                FrameCodec.write(socket.getOutputStream(), new ClassReloadRequest("class", token,
                        java.util.Collections.singletonList(new ClassUpdate(RELOADABLE_CLASS, replacement))));
                ReloadResponse classResponse = (ReloadResponse) FrameCodec.read(socket.getInputStream());
                assertEquals("class", classResponse.getRequestId());
                assertEquals(OperationStatus.SUCCESS, classResponse.getStatus());
                assertEquals("V2", fixture.command("CLASS"));
            }

            try {
                waitFor("Agent descriptor cleanup", () -> Files.notExists(sessionPath));
            } catch (AssertionError failure) {
                throw new AssertionError(readAgentLogs(launchDirectory), failure);
            }
            waitFor("Agent thread cleanup", () -> "0".equals(fixture.commandUnchecked("THREADS")));
            waitFor("logger lock cleanup", () -> !containsFileWithSuffix(launchDirectory, ".lck"));
            assertEquals("0", fixture.command("CONFIGURATIONS"));

            fixture.stopGracefully();
            String logs = readAgentLogs(launchDirectory);
            assertTrue(logs.contains("event=CLIENT_EOF"));
            assertTrue(logs.contains("event=EXECUTOR_SHUTDOWN"));
            assertTrue(logs.contains("event=RESOURCE_SNAPSHOT"));
            assertTrue(logs.contains("event=CLEANUP_COMPLETE"));
            assertTrue(hasFinalLifecycleSnapshot(logs), logs);
            assertTrue(logs.contains("event=LOGGER_CLOSE"));
            assertFalse(logs.contains(token));
            assertFalse(logs.contains("<mapper"));
        }
    }

    private void runProcessStopFlow(String version, Path javaHome) throws Exception {
        Path launchDirectory = Files.createDirectory(temporaryDirectory.resolve("stop-jdk-" + version));
        Path sessionPath = launchDirectory.resolve("session.properties");
        Path logPattern = launchDirectory.resolve("agent-%g.log");
        String launchId = UUID.randomUUID().toString();
        String token = randomToken();

        try (JvmFixtureProcess fixture = JvmFixtureProcess.start(javaHome,
                requiredPath("hotreload.agent.jar"), requiredText("hotreload.fixture.classpath"),
                sessionPath, logPattern, token, launchId)) {
            fixture.awaitReady();
            waitFor("session descriptor", () -> Files.isRegularFile(sessionPath));
            SessionDescriptor descriptor = SessionDescriptor.read(sessionPath);
            try (Socket socket = new Socket(descriptor.getAddress(), descriptor.getPort())) {
                socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(10));
                FrameCodec.write(socket.getOutputStream(), new HelloRequest("hello-stop", token, launchId));
                HelloResponse hello = (HelloResponse) FrameCodec.read(socket.getInputStream());
                assertEquals("hello-stop", hello.getRequestId());
                fixture.stopGracefully();
            }

            waitFor("shutdown descriptor cleanup", () -> Files.notExists(sessionPath));
            waitFor("shutdown logger lock cleanup", () -> !containsFileWithSuffix(launchDirectory, ".lck"));
            String logs = readAgentLogs(launchDirectory);
            assertTrue(logs.contains("event=EXECUTOR_SHUTDOWN"), logs);
            assertTrue(logs.contains("remainingQueue=0"), logs);
            assertTrue(logs.contains("event=RESOURCE_SNAPSHOT"), logs);
            assertTrue(logs.matches("(?s).*event=RESOURCE_SNAPSHOT[^\\r\\n]*activeSession=false"
                    + "[^\\r\\n]*openSocket=0[^\\r\\n]*queueSize=0.*"), logs);
            assertTrue(logs.contains("event=CLEANUP_COMPLETE"), logs);
            assertTrue(hasFinalLifecycleSnapshot(logs), logs);
            assertTrue(logs.contains("event=LOGGER_CLOSE"), logs);
            assertFalse(logs.contains("event=SOCKET_ACCEPT_FAILED"), logs);
            assertFalse(logs.contains(token));
        }
    }

    private static Path replacementClassPath() {
        return requiredPath("hotreload.fixture.reload.classes")
                .resolve(RELOADABLE_CLASS.replace('.', java.io.File.separatorChar) + ".class");
    }

    private static byte[] mapperXml(String value) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
                + "<mapper namespace=\"probe.Mapper\"><select id=\"value\" resultType=\"string\">"
                + "SELECT '" + value + "'</select></mapper>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void waitFor(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean(), description + " did not complete");
    }

    private static boolean containsFileWithSuffix(Path directory, String suffix) {
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(suffix));
        } catch (Exception e) {
            return true;
        }
    }

    private static String readAgentLogs(Path directory) throws Exception {
        StringBuilder logs = new StringBuilder();
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths
                    .filter(candidate -> candidate.getFileName().toString().startsWith("agent-"))
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".log"))::iterator) {
                logs.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        return logs.toString();
    }

    private static boolean hasFinalLifecycleSnapshot(String logs) {
        return logs.matches("(?s).*event=LIFECYCLE_CLEANUP closeFailures=\\d+ "
                + "trackedConfigurations=0.*");
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
