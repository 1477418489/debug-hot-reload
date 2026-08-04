package dev.hotreload.protocol.message;

import java.util.Objects;

public final class ReloadItemResult {
    private final String itemId;
    private final OperationStatus status;
    private final ReloadErrorCode errorCode;
    private final String message;
    private final String diagnostic;

    public ReloadItemResult(String itemId, OperationStatus status, ReloadErrorCode errorCode, String message, String diagnostic) {
        this.itemId = MessageChecks.text(itemId, "itemId");
        this.status = Objects.requireNonNull(status, "status");
        this.errorCode = errorCode;
        this.message = MessageChecks.optionalText(message, "message");
        this.diagnostic = MessageChecks.optionalText(diagnostic, "diagnostic");
    }

    public String getItemId() { return itemId; }
    public OperationStatus getStatus() { return status; }
    public ReloadErrorCode getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public String getDiagnostic() { return diagnostic; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ReloadItemResult)) return false;
        ReloadItemResult that = (ReloadItemResult) other;
        return itemId.equals(that.itemId) && status == that.status && errorCode == that.errorCode
                && message.equals(that.message) && diagnostic.equals(that.diagnostic);
    }

    @Override public int hashCode() { return Objects.hash(itemId, status, errorCode, message, diagnostic); }
}
