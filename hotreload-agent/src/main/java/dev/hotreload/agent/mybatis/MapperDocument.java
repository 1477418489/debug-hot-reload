package dev.hotreload.agent.mybatis;

final class MapperDocument {
    private final String resourceId;
    private final String namespace;
    private final byte[] sha256;
    private final byte[] content;

    MapperDocument(String resourceId, String namespace, byte[] sha256, byte[] content) {
        this.resourceId = resourceId;
        this.namespace = namespace;
        this.sha256 = sha256.clone();
        this.content = content.clone();
    }

    String getResourceId() { return resourceId; }
    String getNamespace() { return namespace; }
    byte[] getSha256() { return sha256.clone(); }
    byte[] getContent() { return content.clone(); }
}
