package dev.hotreload.agent.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AgentOptions {
    private static final int TOKEN_BYTES = 32;

    private final Path sessionPath;
    private final Path logPath;
    private final byte[] token;
    private final String launchId;
    private final boolean verboseLogs;

    private AgentOptions(Path sessionPath, Path logPath, byte[] token, String launchId, boolean verboseLogs) {
        this.sessionPath = sessionPath;
        this.logPath = logPath;
        this.token = token;
        this.launchId = launchId;
        this.verboseLogs = verboseLogs;
    }

    public static AgentOptions parse(String rawOptions) {
        if (rawOptions == null || rawOptions.isEmpty()) {
            throw new IllegalArgumentException("Agent options must not be empty");
        }
        Map<String, String> values = parseValues(rawOptions);
        boolean legacyToken = values.containsKey("token");
        boolean tokenFile = values.containsKey("tokenFile");
        boolean hasVerbose = values.containsKey("verbose");
        int expected = hasVerbose ? 5 : 4;
        if (values.size() != expected || !values.containsKey("session") || !values.containsKey("log")
                || (!legacyToken && !tokenFile) || values.containsKey("token") && values.containsKey("tokenFile")
                || !values.containsKey("launch")) {
            throw new IllegalArgumentException("Agent options must contain session, log, one token source and launch");
        }
        boolean verboseLogs = hasVerbose && parseBooleanFlag(values.get("verbose"));

        Path sessionPath = decodePath(values.get("session"), "session");
        Path logPath = decodePath(values.get("log"), "log");
        Path sessionParent = sessionPath.getParent();
        Path logParent = logPath.getParent();
        if (sessionParent == null || !sessionParent.equals(logParent)
                || hasSymbolicComponent(sessionParent)
                || !Files.isDirectory(sessionParent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Session and log paths must share an existing launch directory");
        }
        if (Files.isSymbolicLink(sessionPath) || Files.isSymbolicLink(logPath)
                || sessionPath.equals(logPath)) throw new IllegalArgumentException("Session and log paths must differ");

        String tokenValue = values.get("token");
        byte[] token = legacyToken && !tokenValue.startsWith("file:") ? decodeToken(tokenValue)
                : readAndConsumeTokenFile(decodePath(legacyToken
                        ? tokenValue.substring("file:".length()) : values.get("tokenFile"), "tokenFile"),
                        sessionParent);
        String launchId = parseLaunchId(values.get("launch"));
        return new AgentOptions(sessionPath, logPath, token, launchId, verboseLogs);
    }

    public Path getSessionPath() { return sessionPath; }
    public Path getLogPath() { return logPath; }
    public String getLaunchId() { return launchId; }
    public boolean isVerboseLogs() { return verboseLogs; }
    public byte[] getTokenBytes() { return token.clone(); }

    public boolean matchesToken(String candidate) {
        if (candidate == null) return false;
        try {
            return MessageDigest.isEqual(token, Base64.getUrlDecoder().decode(candidate));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Map<String, String> parseValues(String rawOptions) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        String[] entries = rawOptions.split(",", -1);
        for (String entry : entries) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("Invalid agent option");
            }
            String key = entry.substring(0, separator);
            String value = entry.substring(separator + 1);
            if (values.put(key, value) != null) throw new IllegalArgumentException("Duplicate agent option: " + key);
        }
        return values;
    }

    private static Path decodePath(String encoded, String name) {
        final String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " path is not valid Base64URL", e);
        }
        Path path = Paths.get(decoded);
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalArgumentException(name + " path must be absolute and normalized");
        }
        return path;
    }

    private static boolean hasSymbolicComponent(Path path) {
        Path root = path.getRoot();
        Path current = root;
        for (Path component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    private static byte[] decodeToken(String encoded) {
        final byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("token is not valid Base64URL", e);
        }
        if (decoded.length != TOKEN_BYTES) throw new IllegalArgumentException("token must contain 256 bits");
        return decoded;
    }

    private static byte[] readAndConsumeTokenFile(Path tokenPath, Path sessionParent) {
        if (tokenPath == null || tokenPath.getParent() == null
                || !tokenPath.getParent().equals(sessionParent)
                || Files.isSymbolicLink(tokenPath)
                || !Files.isRegularFile(tokenPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("tokenFile must be a regular file in the launch directory");
        }
        byte[] bytes = null;
        RuntimeException failure = null;
        try (SeekableByteChannel channel = Files.newByteChannel(tokenPath,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(64);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64);
            while (channel.read(buffer) > 0) {
                buffer.flip();
                if (output.size() + buffer.remaining() > 128) {
                    throw new IllegalArgumentException("tokenFile is too large");
                }
                while (buffer.hasRemaining()) output.write(buffer.get());
                buffer.clear();
            }
            bytes = output.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("tokenFile cannot be read", e);
        } finally {
            try {
                if (!Files.deleteIfExists(tokenPath)) {
                    if (failure == null) failure = new IllegalArgumentException("tokenFile could not be consumed");
                }
            } catch (IOException e) {
                failure = new IllegalArgumentException("tokenFile could not be consumed", e);
            }
        }
        if (failure != null) throw failure;
        if (bytes == null) throw new IllegalArgumentException("tokenFile is empty");
        String encoded = new String(bytes, StandardCharsets.US_ASCII).trim();
        return decodeToken(encoded);
    }

    private static boolean parseBooleanFlag(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v);
    }

    private static String parseLaunchId(String value) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) throw new IllegalArgumentException("launch must be a canonical UUID");
            return canonical;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("launch must be a canonical UUID", e);
        }
    }

    @Override public String toString() {
        return "AgentOptions{sessionPath='" + sessionPath.getFileName() + "', logPath='"
                + logPath.getFileName() + "', launchId='" + launchId + "'}";
    }
}
