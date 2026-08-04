package dev.hotreload.protocol;

public final class ProtocolLimits {
    public static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    /** The first frame is a Hello and must never need the mutation payload budget. */
    public static final int MAX_HELLO_FRAME_BYTES = 64 * 1024;
    public static final int MAX_ITEM_BYTES = 8 * 1024 * 1024;
    public static final int MAX_CLASS_BATCH = 256;
    public static final int MAX_STRING_BYTES = 16 * 1024;
    public static final int MAX_DIAGNOSTIC_BYTES = 16 * 1024;

    private ProtocolLimits() {
    }
}
