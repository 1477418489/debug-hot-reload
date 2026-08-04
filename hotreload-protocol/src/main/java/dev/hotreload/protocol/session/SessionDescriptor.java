package dev.hotreload.protocol.session;

import dev.hotreload.protocol.ProtocolLimits;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SessionDescriptor {
    public static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;

    private final String launchId;
    private final int protocol;
    private final String address;
    private final int port;
    private final byte[] proof;

    public SessionDescriptor(String launchId, int protocol, int port) {
        this(launchId, protocol, port, null);
    }

    private SessionDescriptor(String launchId, int protocol, int port, byte[] proof) {
        if (launchId == null || launchId.isEmpty() || launchId.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("launchId is invalid");
        }
        if (protocol <= 0) throw new IllegalArgumentException("protocol must be positive");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port is invalid");
        this.launchId = launchId;
        this.protocol = protocol;
        this.address = LOOPBACK_ADDRESS;
        this.port = port;
        this.proof = proof == null ? null : proof.clone();
    }

    public static SessionDescriptor authenticated(String launchId, int protocol, int port,
                                                  byte[] authenticationKey) {
        SessionDescriptor descriptor = new SessionDescriptor(launchId, protocol, port);
        return new SessionDescriptor(launchId, protocol, port,
                descriptor.computeProof(authenticationKey));
    }

    public String getLaunchId() { return launchId; }
    public int getProtocol() { return protocol; }
    public String getAddress() { return address; }
    public int getPort() { return port; }

    public boolean verifies(String base64UrlToken) {
        if (proof == null || base64UrlToken == null) return false;
        try {
            return MessageDigest.isEqual(proof, computeProof(Base64.getUrlDecoder().decode(base64UrlToken)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void writeAtomically(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || hasSymbolicComponent(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Session directory does not exist");
        }
        byte[] effectiveProof = proof == null ? discoverLaunchCredential(parent) : proof;
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString() + ".", ".tmp");
        boolean moved = false;
        try {
            Properties properties = new Properties();
            properties.setProperty("launchId", launchId);
            properties.setProperty("protocol", Integer.toString(protocol));
            properties.setProperty("address", address);
            properties.setProperty("port", Integer.toString(port));
            if (effectiveProof != null) {
                properties.setProperty("proof", Base64.getUrlEncoder().withoutPadding().encodeToString(effectiveProof));
            }
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, null);
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic session descriptor replacement is not supported", e);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    public static SessionDescriptor read(Path source) throws IOException {
        if (source == null) throw new NullPointerException("source");
        Path absolute = source.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || hasSymbolicComponent(parent) || Files.isSymbolicLink(absolute)
                || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Session descriptor must be a regular non-symbolic file");
        }
        Properties properties = new Properties();
        try (InputStream input = new ByteArrayInputStream(readBounded(absolute))) {
            properties.load(input);
        }
        Set<String> expected = new HashSet<String>();
        expected.add("launchId");
        expected.add("protocol");
        expected.add("address");
        expected.add("port");
        if (properties.containsKey("proof")) expected.add("proof");
        if (properties.size() != expected.size() || !properties.stringPropertyNames().equals(expected)
                || !LOOPBACK_ADDRESS.equals(properties.getProperty("address"))) {
            throw new IOException("Invalid session descriptor");
        }
        try {
            byte[] proof = null;
            if (properties.containsKey("proof")) {
                proof = Base64.getUrlDecoder().decode(properties.getProperty("proof"));
                if (proof.length != 32) throw new IllegalArgumentException("Invalid descriptor proof");
            }
            return new SessionDescriptor(properties.getProperty("launchId"),
                    Integer.parseInt(properties.getProperty("protocol")),
                    Integer.parseInt(properties.getProperty("port")), proof);
        } catch (RuntimeException e) {
            throw new IOException("Invalid session descriptor", e);
        }
    }

    private static byte[] readBounded(Path source) throws IOException {
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        long declaredSize = before.size();
        if (declaredSize <= 0L || declaredSize > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("Session descriptor exceeds the size limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) declaredSize);
        byte[] buffer = new byte[4096];
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (read > MAX_DESCRIPTOR_BYTES - output.size()) {
                    throw new IOException("Session descriptor exceeds the size limit");
                }
                output.write(buffer, 0, read);
            }
        }
        BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("Session descriptor changed while it was being read");
        }
        return output.toByteArray();
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

    private byte[] computeProof(byte[] authenticationKey) {
        if (authenticationKey == null || authenticationKey.length != 32) {
            throw new IllegalArgumentException("authentication key must contain 256 bits");
        }
        String canonical = launchId + "\n" + protocol + "\n" + address + "\n" + port;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authenticationKey, "HmacSHA256"));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private byte[] discoverLaunchCredential(Path parent) {
        Path credential = parent.resolve("credential.token");
        if (Files.isSymbolicLink(credential)
                || !Files.isRegularFile(credential, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            byte[] raw = readCredential(credential);
            byte[] key = Base64.getUrlDecoder().decode(new String(raw, StandardCharsets.US_ASCII).trim());
            return key.length == 32 ? computeProof(key) : null;
        } catch (RuntimeException | IOException ignored) {
            return null;
        }
    }

    private static byte[] readCredential(Path credential) throws IOException {
        BasicFileAttributes before = Files.readAttributes(credential, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() <= 0L || before.size() > 128L) {
            throw new IOException("Invalid launch credential");
        }
        byte[] raw = new byte[(int) before.size()];
        try (InputStream input = Files.newInputStream(credential, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            int offset = 0;
            while (offset < raw.length) {
                int read = input.read(raw, offset, raw.length - offset);
                if (read < 0) throw new IOException("Truncated launch credential");
                if (read == 0) continue;
                offset += read;
            }
            if (input.read() >= 0) throw new IOException("Oversized launch credential");
        }
        BasicFileAttributes after = Files.readAttributes(credential, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("Launch credential changed while it was being read");
        }
        return raw;
    }

    @Override public String toString() {
        return "SessionDescriptor{launchId='" + launchId + "', protocol=" + protocol + ", address='" + address + "'}";
    }
}
