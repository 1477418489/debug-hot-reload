package dev.hotreload.protocol.message;

public enum OperationStatus {
    SUCCESS(1), SKIPPED(2), FAILED(3), RESTART_REQUIRED(4);

    private final int wireId;
    OperationStatus(int wireId) { this.wireId = wireId; }
    public int wireId() { return wireId; }
    public int getWireId() { return wireId; }

    public static OperationStatus fromWireId(int id) {
        for (OperationStatus value : values()) if (value.wireId == id) return value;
        throw new IllegalArgumentException("Unknown operation status ID: " + id);
    }
}
