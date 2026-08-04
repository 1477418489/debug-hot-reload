package dev.hotreload.protocol.message;

import dev.hotreload.protocol.resource.ResourceId;
import java.util.Arrays;
import java.util.Objects;

public final class MapperUpdate {
    private final String resourceId;
    private final byte[] sha256;
    private final byte[] content;

    public MapperUpdate(String resourceId, byte[] sha256, byte[] content) {
        this.resourceId = ResourceId.of(resourceId).value();
        byte[] digest = MessageChecks.bytes(sha256, "sha256");
        if (digest.length != 32) {
            throw new IllegalArgumentException("sha256 must contain exactly 32 bytes");
        }
        this.sha256 = digest;
        this.content = MessageChecks.bytes(content, "content");
    }

    public String getResourceId() { return resourceId; }
    public byte[] getSha256() { return sha256.clone(); }
    public byte[] getContent() { return content.clone(); }
    public int getContentLength() { return content.length; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MapperUpdate)) return false;
        MapperUpdate that = (MapperUpdate) other;
        return resourceId.equals(that.resourceId) && Arrays.equals(sha256, that.sha256) && Arrays.equals(content, that.content);
    }

    @Override public int hashCode() { return 31 * Objects.hash(resourceId, Arrays.hashCode(sha256)) + Arrays.hashCode(content); }
}
