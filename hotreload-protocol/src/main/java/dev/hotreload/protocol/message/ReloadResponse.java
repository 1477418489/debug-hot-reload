package dev.hotreload.protocol.message;

import dev.hotreload.protocol.ProtocolLimits;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ReloadResponse {
    private final String requestId;
    private final OperationStatus status;
    private final ReloadErrorCode errorCode;
    private final String message;
    private final List<ReloadItemResult> items;

    public ReloadResponse(String requestId, OperationStatus status, ReloadErrorCode errorCode, String message,
                          List<ReloadItemResult> items) {
        this.requestId = MessageChecks.text(requestId, "requestId");
        this.status = Objects.requireNonNull(status, "status");
        this.errorCode = errorCode;
        this.message = MessageChecks.optionalText(message, "message");
        if (items == null) throw new IllegalArgumentException("items must not be null");
        if (items.size() > ProtocolLimits.MAX_CLASS_BATCH) throw new IllegalArgumentException("result items exceed the protocol limit");
        ArrayList<ReloadItemResult> copy = new ArrayList<ReloadItemResult>(items.size());
        for (ReloadItemResult item : items) copy.add(Objects.requireNonNull(item, "item"));
        this.items = Collections.unmodifiableList(copy);
    }

    public String getRequestId() { return requestId; }
    public OperationStatus getStatus() { return status; }
    public ReloadErrorCode getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public List<ReloadItemResult> getItems() { return items; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ReloadResponse)) return false;
        ReloadResponse that = (ReloadResponse) other;
        return requestId.equals(that.requestId) && status == that.status && errorCode == that.errorCode
                && message.equals(that.message) && items.equals(that.items);
    }

    @Override public int hashCode() { return Objects.hash(requestId, status, errorCode, message, items); }
}
