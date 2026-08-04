package dev.hotreload.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgentOptionsTest {
    @TempDir Path tempDirectory;

    @Test void parsesTheFixedDebugLaunchFormat() {
        Path launchDirectory = tempDirectory.resolve("launch").toAbsolutePath().normalize();
        launchDirectory.toFile().mkdirs();
        Path session = launchDirectory.resolve("session.properties");
        Path log = launchDirectory.resolve("agent.log");
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String launch = UUID.randomUUID().toString();

        AgentOptions options = AgentOptions.parse(options(session, log, token, launch));

        assertEquals(session, options.getSessionPath());
        assertEquals(log, options.getLogPath());
        assertEquals(launch, options.getLaunchId());
        assertTrue(options.matchesToken(token));
        assertFalse(options.matchesToken(token + "x"));
        assertFalse(options.toString().contains(token));
    }

    @Test void rejectsUnknownKeysRelativePathsAndDifferentLaunchDirectories() {
        Path launchDirectory = tempDirectory.toAbsolutePath().normalize();
        Path session = launchDirectory.resolve("session.properties");
        Path log = launchDirectory.resolve("agent.log");
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String launch = UUID.randomUUID().toString();

        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse(options(session, log, token, launch) + ",mode=debug"));
        AgentOptions verbose = AgentOptions.parse(options(session, log, token, launch) + ",verbose=true");
        assertTrue(verbose.isVerboseLogs());
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse(options(java.nio.file.Paths.get("session.properties"), log, token, launch)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse(options(session, tempDirectory.resolve("other/agent.log").toAbsolutePath(), token, launch)));
    }

    @Test void consumesAFileBackedTokenWithoutPuttingTheBearerInOptions() throws Exception {
        Path launchDirectory = tempDirectory.resolve("file-token").toAbsolutePath().normalize();
        Files.createDirectories(launchDirectory);
        Path session = launchDirectory.resolve("session.properties");
        Path log = launchDirectory.resolve("agent.log");
        Path tokenFile = launchDirectory.resolve("credential.token");
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        Files.write(tokenFile, token.getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE_NEW);
        String launch = UUID.randomUUID().toString();

        AgentOptions options = AgentOptions.parse("session=" + encode(session.toString())
                + ",log=" + encode(log.toString())
                + ",token=file:" + encode(tokenFile.toString()) + ",launch=" + launch);

        assertTrue(options.matchesToken(token));
        assertFalse(Files.exists(tokenFile));
    }

    @Test void rejectsALaunchDirectoryReachedThroughASymbolicAncestor() throws Exception {
        Path realParent = tempDirectory.resolve("real-parent");
        Files.createDirectories(realParent);
        Path link = tempDirectory.resolve("linked-parent");
        try {
            Files.createSymbolicLink(link, realParent);
        } catch (Exception unsupported) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: "
                    + unsupported.getClass().getSimpleName());
        }
        Path launchDirectory = link.resolve("launch");
        Files.createDirectories(launchDirectory);
        Path session = launchDirectory.resolve("session.properties");
        Path log = launchDirectory.resolve("agent.log");
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse(options(session, log, token, UUID.randomUUID().toString())));
    }

    @Test void acceptsOptionalVerboseFlag() {
        Path launchDirectory = tempDirectory.resolve("verbose-launch").toAbsolutePath().normalize();
        launchDirectory.toFile().mkdirs();
        Path session = launchDirectory.resolve("session.properties");
        Path log = launchDirectory.resolve("agent.log");
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String launch = UUID.randomUUID().toString();
        AgentOptions options = AgentOptions.parse(options(session, log, token, launch) + ",verbose=1");
        assertTrue(options.isVerboseLogs());
        AgentOptions off = AgentOptions.parse(options(session, log, token, UUID.randomUUID().toString()));
        assertFalse(off.isVerboseLogs());
    }

    private static String options(Path session, Path log, String token, String launch) {
        return "session=" + encode(session.toString()) + ",log=" + encode(log.toString())
                + ",token=" + token + ",launch=" + launch;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}