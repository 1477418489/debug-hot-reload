package dev.hotreload.protocol.resource;

import dev.hotreload.protocol.ProtocolLimits;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ResourceId {
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    private final String value;

    private ResourceId(String value) { this.value = value; }

    public static ResourceId of(String raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("resourceId must not be empty");
        String normalized = raw.replace('\\', '/');
        if (normalized.startsWith("/") || DRIVE.matcher(normalized).matches() || SCHEME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("resourceId must be a classpath-relative path");
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("resourceId exceeds the protocol limit");
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("resourceId contains an invalid path segment");
            }
        }
        return new ResourceId(normalized);
    }

    public String value() { return value; }
    public String getValue() { return value; }
    @Override public String toString() { return value; }
    @Override public boolean equals(Object other) { return other instanceof ResourceId && value.equals(((ResourceId) other).value); }
    @Override public int hashCode() { return Objects.hash(value); }
}
