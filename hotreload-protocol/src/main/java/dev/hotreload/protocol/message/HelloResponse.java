package dev.hotreload.protocol.message;

import java.util.Objects;

public final class HelloResponse {
    private final String requestId;
    private final int protocolVersion;
    private final boolean classRedefineSupported;
    private final boolean enhancedRedefineSupported;
    private final String targetJavaVersion;
    private final int configurationCount;

    public HelloResponse(String requestId, int protocolVersion, boolean classRedefineSupported,
                         String targetJavaVersion, int configurationCount) {
        this(requestId, protocolVersion, classRedefineSupported, false, targetJavaVersion, configurationCount);
    }

    public HelloResponse(String requestId, int protocolVersion, boolean classRedefineSupported,
                         boolean enhancedRedefineSupported, String targetJavaVersion, int configurationCount) {
        this.requestId = MessageChecks.text(requestId, "requestId");
        if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion must be positive");
        if (configurationCount < 0) throw new IllegalArgumentException("configurationCount must not be negative");
        this.protocolVersion = protocolVersion;
        this.classRedefineSupported = classRedefineSupported;
        this.enhancedRedefineSupported = enhancedRedefineSupported;
        this.targetJavaVersion = MessageChecks.text(targetJavaVersion, "targetJavaVersion");
        this.configurationCount = configurationCount;
    }

    public String getRequestId() { return requestId; }
    public int getProtocolVersion() { return protocolVersion; }
    public boolean isClassRedefineSupported() { return classRedefineSupported; }
    /** True when the target JVM supports structural redefine (DCEVM / JBR enhanced). */
    public boolean isEnhancedRedefineSupported() { return enhancedRedefineSupported; }
    public String getTargetJavaVersion() { return targetJavaVersion; }
    public int getConfigurationCount() { return configurationCount; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HelloResponse)) return false;
        HelloResponse that = (HelloResponse) other;
        return protocolVersion == that.protocolVersion
                && classRedefineSupported == that.classRedefineSupported
                && enhancedRedefineSupported == that.enhancedRedefineSupported
                && configurationCount == that.configurationCount
                && requestId.equals(that.requestId)
                && targetJavaVersion.equals(that.targetJavaVersion);
    }

    @Override public int hashCode() {
        return Objects.hash(requestId, protocolVersion, classRedefineSupported,
                enhancedRedefineSupported, targetJavaVersion, configurationCount);
    }
}
