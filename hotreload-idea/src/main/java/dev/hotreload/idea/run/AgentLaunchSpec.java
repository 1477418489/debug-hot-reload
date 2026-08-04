package dev.hotreload.idea.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.LinkOption;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import dev.hotreload.idea.change.PathSafety;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public final class AgentLaunchSpec {
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String launchId;
    private final String token;
    private final Path launchDirectory;
    private final Path sessionPath;
    private final Path logPath;
    private final Path credentialPath;
    private final Object credentialFileKey;
    private final String agentOptions;
    private final String vmArgument;

    private AgentLaunchSpec(String launchId, String token, Path launchDirectory, Path sessionPath,
                            Path logPath, Path credentialPath, Object credentialFileKey,
                            String agentOptions, String vmArgument) {
        this.launchId = launchId;
        this.token = token;
        this.launchDirectory = launchDirectory;
        this.sessionPath = sessionPath;
        this.logPath = logPath;
        this.credentialPath = credentialPath;
        this.credentialFileKey = credentialFileKey;
        this.agentOptions = agentOptions;
        this.vmArgument = vmArgument;
    }

    public static AgentLaunchSpec create(Path launchRoot, Path agentJar) throws IOException {
        return create(launchRoot, agentJar, false);
    }

    public static AgentLaunchSpec create(Path launchRoot, Path agentJar, boolean verboseLogs) throws IOException {
        Path root = requireAbsoluteNormalized(launchRoot, "launchRoot");
        Path agent = requireAbsoluteNormalized(agentJar, "agentJar");
        agent = PathSafety.realFile(agent);
        root = PathSafety.schedulingDirectory(root);
        Files.createDirectories(root);
        root = PathSafety.realDirectory(root);

        String launchId = UUID.randomUUID().toString();
        Path launchDirectory = root.resolve(launchId);
        Files.createDirectory(launchDirectory);
        Path sessionPath = launchDirectory.resolve("session.properties");
        Path logPath = launchDirectory.resolve("agent.log");
        Path credentialPath = launchDirectory.resolve("credential.token");
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String options = "session=" + encode(sessionPath.toString())
                + ",log=" + encode(logPath.toString())
                + ",token=file:" + encode(credentialPath.toString())
                + ",launch=" + launchId
                + (verboseLogs ? ",verbose=true" : "");
        Files.write(credentialPath, token.getBytes(StandardCharsets.US_ASCII),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(credentialPath, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and filesystems without POSIX ACLs rely on the private launch directory.
        }
        Object credentialFileKey = Files.readAttributes(credentialPath, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        return new AgentLaunchSpec(launchId, token, launchDirectory, sessionPath, logPath, credentialPath,
                credentialFileKey,
                options, "-javaagent:" + agent + "=" + options);
    }

    public String getLaunchId() { return launchId; }
    public String getToken() { return token; }
    public Path getLaunchDirectory() { return launchDirectory; }
    public Path getSessionPath() { return sessionPath; }
    public Path getLogPath() { return logPath; }
    public Path getCredentialPath() { return credentialPath; }
    /** Returns the exact options passed to the agent; it contains a credential path, never the bearer token. */
    public String getAgentOptions() { return agentOptions; }
    public String getVmArgument() { return vmArgument; }

    public void deleteCredentialIfPresent() throws IOException {
        if (!Files.exists(credentialPath, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attributes = Files.readAttributes(credentialPath, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(credentialPath) || !attributes.isRegularFile()
                || !Objects.equals(credentialFileKey, attributes.fileKey())) {
            throw new IOException("credential path ownership changed");
        }
        Files.deleteIfExists(credentialPath);
    }

    private static Path requireAbsoluteNormalized(Path path, String name) {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalArgumentException(name + " must be absolute and normalized");
        }
        return path;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override public String toString() {
        return "AgentLaunchSpec{launchId='" + launchId + "', launchDirectory='"
                + launchDirectory.getFileName() + "'}";
    }
}
