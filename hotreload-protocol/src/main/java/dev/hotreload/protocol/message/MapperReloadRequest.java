package dev.hotreload.protocol.message;

import java.util.Objects;

public final class MapperReloadRequest implements ReloadRequest {
    private final String requestId;
    private final String token;
    private final MapperUpdate update;

    public MapperReloadRequest(String requestId, String token, MapperUpdate update) {
        this.requestId = MessageChecks.text(requestId, "requestId");
        this.token = MessageChecks.text(token, "token");
        this.update = Objects.requireNonNull(update, "update");
    }

    public String getRequestId() { return requestId; }
    public String getToken() { return token; }
    public MapperUpdate getUpdate() { return update; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MapperReloadRequest)) return false;
        MapperReloadRequest that = (MapperReloadRequest) other;
        return requestId.equals(that.requestId) && token.equals(that.token) && update.equals(that.update);
    }

    @Override public int hashCode() { return Objects.hash(requestId, token, update); }

    @Override public String toString() { return "MapperReloadRequest{requestId='" + requestId + "', resourceId='" + update.getResourceId() + "'}"; }
}
