package dev.hotreload.protocol.message;

import dev.hotreload.protocol.ProtocolLimits;
import dev.hotreload.protocol.resource.ResourceId;

import java.util.Objects;

public final class ResourceReloadRequest implements ReloadRequest {
    private final String requestId;
    private final String token;
    private final String resourcePath;
    private final byte[] content;
    private final String contentType;

    public ResourceReloadRequest(String requestId, String token, String resourcePath,
                                 byte[] content, String contentType) {
        this.requestId = MessageChecks.text(requestId, "requestId");
        this.token = MessageChecks.text(token, "token");
        this.resourcePath = ResourceId.of(resourcePath).value();
        if (content == null) throw new IllegalArgumentException("content must not be null");
        if (content.length > ProtocolLimits.MAX_ITEM_BYTES) {
            throw new IllegalArgumentException("content exceeds the protocol limit");
        }
        this.content = content.clone();
        this.contentType = contentType == null || contentType.isEmpty()
                ? "properties" : MessageChecks.text(contentType, "contentType");
    }

    public String getRequestId() { return requestId; }
    public String getToken() { return token; }
    public String getResourcePath() { return resourcePath; }
    public byte[] getContent() { return content.clone(); }
    public int getContentLength() { return content.length; }
    public String getContentType() { return contentType; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ResourceReloadRequest)) return false;
        ResourceReloadRequest that = (ResourceReloadRequest) other;
        return requestId.equals(that.requestId)
                && token.equals(that.token)
                && resourcePath.equals(that.resourcePath)
                && contentType.equals(that.contentType)
                && java.util.Arrays.equals(content, that.content);
    }

    @Override public int hashCode() {
        return Objects.hash(requestId, token, resourcePath, contentType, java.util.Arrays.hashCode(content));
    }
}
