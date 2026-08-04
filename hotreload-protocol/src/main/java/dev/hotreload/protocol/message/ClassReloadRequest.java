package dev.hotreload.protocol.message;

import dev.hotreload.protocol.ProtocolLimits;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ClassReloadRequest implements ReloadRequest {
    private final String requestId;
    private final String token;
    private final List<ClassUpdate> updates;

    public ClassReloadRequest(String requestId, String token, List<ClassUpdate> updates) {
        this.requestId = MessageChecks.text(requestId, "requestId");
        this.token = MessageChecks.text(token, "token");
        if (updates == null || updates.isEmpty()) throw new IllegalArgumentException("updates must not be empty");
        if (updates.size() > ProtocolLimits.MAX_CLASS_BATCH) throw new IllegalArgumentException("class batch exceeds the protocol limit");
        ArrayList<ClassUpdate> copy = new ArrayList<ClassUpdate>(updates.size());
        for (ClassUpdate update : updates) copy.add(Objects.requireNonNull(update, "update"));
        this.updates = Collections.unmodifiableList(copy);
    }

    public String getRequestId() { return requestId; }
    public String getToken() { return token; }
    public List<ClassUpdate> getUpdates() { return updates; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ClassReloadRequest)) return false;
        ClassReloadRequest that = (ClassReloadRequest) other;
        return requestId.equals(that.requestId) && token.equals(that.token) && updates.equals(that.updates);
    }

    @Override public int hashCode() { return Objects.hash(requestId, token, updates); }
    @Override public String toString() { return "ClassReloadRequest{requestId='" + requestId + "', count=" + updates.size() + "}"; }
}
