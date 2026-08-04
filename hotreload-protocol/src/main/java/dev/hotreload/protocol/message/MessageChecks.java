package dev.hotreload.protocol.message;

import dev.hotreload.protocol.ProtocolLimits;
import java.nio.charset.StandardCharsets;

final class MessageChecks {
    private MessageChecks() {
    }

    static String text(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_STRING_BYTES) {
            throw new IllegalArgumentException(name + " exceeds the protocol limit");
        }
        return value;
    }

    static String optionalText(String value, String name) {
        if (value == null) {
            return "";
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_DIAGNOSTIC_BYTES) {
            throw new IllegalArgumentException(name + " exceeds the protocol limit");
        }
        return value;
    }

    static byte[] bytes(byte[] value, String name) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (value.length > ProtocolLimits.MAX_ITEM_BYTES) {
            throw new IllegalArgumentException(name + " exceeds the protocol limit");
        }
        return value.clone();
    }
}
