package dev.hotreload.protocol.message;

public enum ReloadErrorCode {
    AUTHENTICATION_FAILED(1),
    PROTOCOL_MISMATCH(2),
    PAYLOAD_TOO_LARGE(3),
    BRIDGE_UNAVAILABLE(4),
    MYBATIS_NOT_FOUND(5),
    MULTIPLE_CONFIGURATIONS_UNSUPPORTED(6),
    RESOURCE_NOT_LOADED(7),
    RESOURCE_ID_AMBIGUOUS(8),
    OWNERSHIP_INCOMPLETE(9),
    CONFIGURATION_DRIFT(10),
    XML_INVALID(11),
    XML_SECURITY_VIOLATION(12),
    XML_RELOAD_FAILED(13),
    ROLLBACK_FAILED(14),
    CLASS_REDEFINE_UNSUPPORTED(15),
    CLASS_NOT_LOADED(16),
    CLASS_AMBIGUOUS(17),
    CLASS_UNMODIFIABLE(18),
    CLASS_VERSION_UNSUPPORTED(19),
    CLASS_STRUCTURE_CHANGED(20),
    CLASS_REDEFINE_FAILED(21),
    RELOAD_BUSY(22),
    INTERNAL_ERROR(23),
    CLASS_NAME_INVALID(24),
    CLASS_DUPLICATE(25),
    SPRING_REBIND_INCOMPLETE(26);

    private final int wireId;
    ReloadErrorCode(int wireId) { this.wireId = wireId; }
    public int wireId() { return wireId; }
    public int getWireId() { return wireId; }

    public static ReloadErrorCode fromWireId(int id) {
        for (ReloadErrorCode value : values()) if (value.wireId == id) return value;
        throw new IllegalArgumentException("Unknown reload error code ID: " + id);
    }
}
