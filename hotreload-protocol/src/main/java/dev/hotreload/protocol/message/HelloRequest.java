package dev.hotreload.protocol.message;

import java.util.Objects;

public final class HelloRequest {
    private final String requestId;
    private final String token;
    private final String launchId;

    public HelloRequest(String requestId, String token, String launchId) {
        this.requestId = MessageChecks.text(requestId, "requestId");
        this.token = MessageChecks.text(token, "token");
        this.launchId = MessageChecks.text(launchId, "launchId");
    }

    public String getRequestId() { return requestId; }
    public String getToken() { return token; }
    public String getLaunchId() { return launchId; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HelloRequest)) return false;
        HelloRequest that = (HelloRequest) other;
        return requestId.equals(that.requestId) && token.equals(that.token) && launchId.equals(that.launchId);
    }

    @Override public int hashCode() { return Objects.hash(requestId, token, launchId); }

    @Override public String toString() {
        return "HelloRequest{requestId='" + requestId + "', launchId='" + launchId + "'}";
    }
}
