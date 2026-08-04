package dev.hotreload.protocol.io;

import dev.hotreload.protocol.ProtocolLimits;
import dev.hotreload.protocol.message.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class FrameCodec {
    private static final int HELLO = 1;
    private static final int MAPPER = 2;
    private static final int CLASSES = 3;
    private static final int RESPONSE = 4;
    private static final int HELLO_RESPONSE = 5;
    private static final int RESOURCE = 6;

    private FrameCodec() {
    }

    public interface FrameLengthAcceptor {
        boolean accept(int frameLength);
    }

    public static final class FrameRejectedException extends IOException {
        private final int frameLength;

        public FrameRejectedException(int frameLength) {
            super("Frame rejected before allocation: " + frameLength);
            this.frameLength = frameLength;
        }

        public int getFrameLength() { return frameLength; }
    }

    public static byte[] encode(Object message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            write(bytes, message);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected in-memory encoding failure", e);
        }
    }

    public static void write(OutputStream output, Object message) throws IOException {
        if (output == null) throw new NullPointerException("output");
        int payloadLength = encodedPayloadLength(message);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(payloadLength);
        writePayload(data, message);
        data.flush();
    }

    public static Object read(InputStream input) throws IOException {
        return read(input, ProtocolLimits.MAX_FRAME_BYTES);
    }

    /**
     * Reads one frame while applying a caller supplied limit before allocating its payload buffer.
     * This is used for the unauthenticated Hello handshake.
     */
    public static Object read(InputStream input, int maxFrameBytes) throws IOException {
        return read(input, maxFrameBytes, null);
    }

    public static Object read(InputStream input, int maxFrameBytes,
                              FrameLengthAcceptor acceptor) throws IOException {
        if (input == null) throw new NullPointerException("input");
        int length = readFrameLength(input, maxFrameBytes);
        if (acceptor != null && !acceptor.accept(length)) {
            throw new FrameRejectedException(length);
        }
        FramePayloadInputStream frame = new FramePayloadInputStream(input, length);
        try {
            Object result = readPayload(new DataInputStream(frame));
            if (frame.getRemaining() != 0) {
                throw new IllegalArgumentException("Trailing fields in frame payload");
            }
            return result;
        } catch (EOFException e) {
            if (frame.getRemaining() == 0) {
                throw new IllegalArgumentException("Truncated frame payload", e);
            }
            throw e;
        }
    }

    public static void writeFrame(OutputStream output, byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > ProtocolLimits.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Invalid frame length");
        }
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    public static byte[] readFrame(InputStream input) throws IOException {
        return readFrame(input, ProtocolLimits.MAX_FRAME_BYTES);
    }

    public static byte[] readFrame(InputStream input, int maxFrameBytes) throws IOException {
        return readFrame(input, maxFrameBytes, null);
    }

    public static byte[] readFrame(InputStream input, int maxFrameBytes,
                                   FrameLengthAcceptor acceptor) throws IOException {
        int length = readFrameLength(input, maxFrameBytes);
        if (acceptor != null && !acceptor.accept(length)) {
            throw new FrameRejectedException(length);
        }
        byte[] payload = new byte[length];
        new DataInputStream(input).readFully(payload);
        return payload;
    }

    private static int readFrameLength(InputStream input, int maxFrameBytes) throws IOException {
        if (input == null) throw new NullPointerException("input");
        if (maxFrameBytes <= 0 || maxFrameBytes > ProtocolLimits.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Invalid frame limit");
        }
        int length = new DataInputStream(input).readInt();
        if (length <= 0 || length > maxFrameBytes) {
            throw new IllegalArgumentException("Invalid frame length: " + length);
        }
        return length;
    }

    /** Discards an already length-checked payload and extracts its bounded request id. */
    public static String discardAndReadRequestId(InputStream input, int payloadLength) throws IOException {
        if (payloadLength <= 0 || payloadLength > ProtocolLimits.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Invalid frame length");
        }
        DataInputStream data = new DataInputStream(input);
        int consumed = 0;
        int type = data.readUnsignedByte();
        consumed++;
        if (type != MAPPER && type != CLASSES && type != RESOURCE) {
            discard(data, payloadLength - consumed);
            return null;
        }
        int idLength = data.readInt();
        consumed += Integer.BYTES;
        if (idLength < 0 || idLength > ProtocolLimits.MAX_STRING_BYTES
                || idLength > payloadLength - consumed) {
            discard(data, payloadLength - consumed);
            return null;
        }
        byte[] id = new byte[idLength];
        data.readFully(id);
        consumed += idLength;
        discard(data, payloadLength - consumed);
        return new String(id, StandardCharsets.UTF_8);
    }

    private static void discard(InputStream input, int bytes) throws IOException {
        if (bytes < 0) throw new IOException("Frame payload length underflow");
        byte[] buffer = new byte[Math.min(8192, Math.max(1, bytes))];
        int remaining = bytes;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException("Truncated frame payload");
            if (read == 0) continue;
            remaining -= read;
        }
    }

    public static HelloRequest decodeHello(byte[] frame) {
        return cast(decodeSingleFrame(frame), HelloRequest.class);
    }

    public static MapperReloadRequest decodeMapper(byte[] frame) {
        return cast(decodeSingleFrame(frame), MapperReloadRequest.class);
    }

    public static HelloResponse decodeHelloResponse(byte[] frame) {
        return cast(decodeSingleFrame(frame), HelloResponse.class);
    }

    public static ClassReloadRequest decodeClasses(byte[] frame) {
        return cast(decodeSingleFrame(frame), ClassReloadRequest.class);
    }

    public static ReloadResponse decodeResponse(byte[] frame) {
        return cast(decodeSingleFrame(frame), ReloadResponse.class);
    }

    public static Object decode(byte[] frame) {
        return decodeSingleFrame(frame);
    }

    private static Object decodeSingleFrame(byte[] frame) {
        if (frame == null) throw new IllegalArgumentException("frame must not be null");
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(frame);
            byte[] payload = readFrame(input);
            if (input.available() != 0) throw new IllegalArgumentException("Trailing bytes after frame");
            return decodePayload(payload);
        } catch (EOFException e) {
            throw new IllegalArgumentException("Truncated frame", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed frame", e);
        }
    }

    private static Object decodePayload(byte[] bytes) {
        try {
            ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
            Object result = readPayload(new DataInputStream(raw));
            if (raw.available() != 0) throw new IllegalArgumentException("Trailing fields in frame payload");
            return result;
        } catch (EOFException e) {
            throw new IllegalArgumentException("Truncated frame payload", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed frame payload", e);
        }
    }

    private static Object readPayload(DataInputStream input) throws IOException {
        int type = input.readUnsignedByte();
        switch (type) {
            case HELLO:
                return new HelloRequest(readString(input), readString(input), readString(input));
            case MAPPER:
                return new MapperReloadRequest(readString(input), readString(input), readMapperUpdate(input));
            case CLASSES:
                return readClassRequest(input);
            case RESPONSE:
                return readResponse(input);
            case HELLO_RESPONSE:
                return new HelloResponse(readString(input), input.readInt(), input.readBoolean(),
                        input.readBoolean(), readString(input), input.readInt());
            case RESOURCE:
                return new ResourceReloadRequest(readString(input), readString(input), readString(input),
                        readResourceBytes(input), readString(input));
            default:
                throw new IllegalArgumentException("Unknown message type: " + type);
        }
    }

    private static void writePayload(DataOutputStream output, Object message) throws IOException {
        if (message instanceof HelloRequest) {
            HelloRequest value = (HelloRequest) message;
            output.writeByte(HELLO);
            writeString(output, value.getRequestId());
            writeString(output, value.getToken());
            writeString(output, value.getLaunchId());
        } else if (message instanceof MapperReloadRequest) {
            MapperReloadRequest value = (MapperReloadRequest) message;
            output.writeByte(MAPPER);
            writeString(output, value.getRequestId());
            writeString(output, value.getToken());
            writeMapperUpdate(output, value.getUpdate());
        } else if (message instanceof ClassReloadRequest) {
            ClassReloadRequest value = (ClassReloadRequest) message;
            output.writeByte(CLASSES);
            writeString(output, value.getRequestId());
            writeString(output, value.getToken());
            output.writeInt(value.getUpdates().size());
            for (ClassUpdate update : value.getUpdates()) {
                writeString(output, update.getBinaryName());
                writeBytes(output, update.getBytecode());
            }
        } else if (message instanceof ReloadResponse) {
            writeResponse(output, (ReloadResponse) message);
        } else if (message instanceof HelloResponse) {
            HelloResponse value = (HelloResponse) message;
            output.writeByte(HELLO_RESPONSE);
            writeString(output, value.getRequestId());
            output.writeInt(value.getProtocolVersion());
            output.writeBoolean(value.isClassRedefineSupported());
            output.writeBoolean(value.isEnhancedRedefineSupported());
            writeString(output, value.getTargetJavaVersion());
            output.writeInt(value.getConfigurationCount());
        } else if (message instanceof ResourceReloadRequest) {
            ResourceReloadRequest value = (ResourceReloadRequest) message;
            output.writeByte(RESOURCE);
            writeString(output, value.getRequestId());
            writeString(output, value.getToken());
            writeString(output, value.getResourcePath());
            writeResourceBytes(output, value.getContent());
            writeString(output, value.getContentType());
        } else {
            throw new IllegalArgumentException("Unsupported message type");
        }
    }

    private static int encodedPayloadLength(Object message) {
        long length = 1L;
        if (message instanceof HelloRequest) {
            HelloRequest value = (HelloRequest) message;
            length += encodedStringLength(value.getRequestId())
                    + encodedStringLength(value.getToken())
                    + encodedStringLength(value.getLaunchId());
        } else if (message instanceof MapperReloadRequest) {
            MapperReloadRequest value = (MapperReloadRequest) message;
            MapperUpdate update = value.getUpdate();
            length += encodedStringLength(value.getRequestId())
                    + encodedStringLength(value.getToken())
                    + encodedStringLength(update.getResourceId())
                    + encodedBytesLength(update.getSha256().length)
                    + encodedBytesLength(update.getContentLength());
        } else if (message instanceof ClassReloadRequest) {
            ClassReloadRequest value = (ClassReloadRequest) message;
            length += encodedStringLength(value.getRequestId())
                    + encodedStringLength(value.getToken()) + Integer.BYTES;
            for (ClassUpdate update : value.getUpdates()) {
                length += encodedStringLength(update.getBinaryName())
                        + encodedBytesLength(update.getBytecodeLength());
            }
        } else if (message instanceof ReloadResponse) {
            ReloadResponse value = (ReloadResponse) message;
            length += encodedStringLength(value.getRequestId())
                    + (2L * Integer.BYTES)
                    + encodedStringLength(value.getMessage()) + Integer.BYTES;
            for (ReloadItemResult item : value.getItems()) {
                length += encodedStringLength(item.getItemId())
                        + (2L * Integer.BYTES)
                        + encodedStringLength(item.getMessage())
                        + encodedStringLength(item.getDiagnostic());
            }
        } else if (message instanceof HelloResponse) {
            HelloResponse value = (HelloResponse) message;
            length += encodedStringLength(value.getRequestId()) + Integer.BYTES + 2L
                    + encodedStringLength(value.getTargetJavaVersion()) + Integer.BYTES;
        } else if (message instanceof ResourceReloadRequest) {
            ResourceReloadRequest value = (ResourceReloadRequest) message;
            length += encodedStringLength(value.getRequestId())
                    + encodedStringLength(value.getToken())
                    + encodedStringLength(value.getResourcePath())
                    + encodedResourceBytesLength(value.getContentLength())
                    + encodedStringLength(value.getContentType());
        } else {
            throw new IllegalArgumentException("Unsupported message type");
        }
        if (length <= 0L || length > ProtocolLimits.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Frame payload exceeds the protocol limit");
        }
        return (int) length;
    }

    private static long encodedStringLength(String value) {
        if (value == null) throw new NullPointerException("value");
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > ProtocolLimits.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("String exceeds protocol limit");
        }
        return Integer.BYTES + (long) bytes;
    }

    private static long encodedBytesLength(int bytes) {
        if (bytes <= 0 || bytes > ProtocolLimits.MAX_ITEM_BYTES) {
            throw new IllegalArgumentException("Invalid item length");
        }
        return Integer.BYTES + (long) bytes;
    }

    private static long encodedResourceBytesLength(int bytes) {
        if (bytes < 0 || bytes > ProtocolLimits.MAX_ITEM_BYTES) {
            throw new IllegalArgumentException("Invalid resource length");
        }
        return Integer.BYTES + (long) bytes;
    }

    private static void writeMapperUpdate(DataOutputStream output, MapperUpdate update) throws IOException {
        writeString(output, update.getResourceId());
        writeBytes(output, update.getSha256());
        writeBytes(output, update.getContent());
    }

    private static MapperUpdate readMapperUpdate(DataInputStream input) throws IOException {
        return new MapperUpdate(readString(input), readBytes(input), readBytes(input));
    }

    private static ClassReloadRequest readClassRequest(DataInputStream input) throws IOException {
        String requestId = readString(input);
        String token = readString(input);
        int count = input.readInt();
        if (count <= 0 || count > ProtocolLimits.MAX_CLASS_BATCH) throw new IllegalArgumentException("Invalid class batch size");
        List<ClassUpdate> updates = new ArrayList<ClassUpdate>(count);
        for (int i = 0; i < count; i++) updates.add(new ClassUpdate(readString(input), readBytes(input)));
        return new ClassReloadRequest(requestId, token, updates);
    }

    private static void writeResponse(DataOutputStream output, ReloadResponse value) throws IOException {
        output.writeByte(RESPONSE);
        writeString(output, value.getRequestId());
        output.writeInt(value.getStatus().wireId());
        output.writeInt(value.getErrorCode() == null ? 0 : value.getErrorCode().wireId());
        writeString(output, value.getMessage());
        output.writeInt(value.getItems().size());
        for (ReloadItemResult item : value.getItems()) {
            writeString(output, item.getItemId());
            output.writeInt(item.getStatus().wireId());
            output.writeInt(item.getErrorCode() == null ? 0 : item.getErrorCode().wireId());
            writeString(output, item.getMessage());
            writeString(output, item.getDiagnostic());
        }
    }

    private static ReloadResponse readResponse(DataInputStream input) throws IOException {
        String requestId = readString(input);
        OperationStatus status = OperationStatus.fromWireId(input.readInt());
        ReloadErrorCode errorCode = readErrorCode(input.readInt());
        String message = readString(input);
        int count = input.readInt();
        if (count < 0 || count > ProtocolLimits.MAX_CLASS_BATCH) throw new IllegalArgumentException("Invalid result item count");
        List<ReloadItemResult> items = new ArrayList<ReloadItemResult>(count);
        for (int i = 0; i < count; i++) {
            items.add(new ReloadItemResult(readString(input), OperationStatus.fromWireId(input.readInt()),
                    readErrorCode(input.readInt()), readString(input), readString(input)));
        }
        return new ReloadResponse(requestId, status, errorCode, message, items);
    }

    private static ReloadErrorCode readErrorCode(int id) {
        return id == 0 ? null : ReloadErrorCode.fromWireId(id);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ProtocolLimits.MAX_STRING_BYTES) throw new IllegalArgumentException("String exceeds protocol limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > ProtocolLimits.MAX_STRING_BYTES) throw new IllegalArgumentException("Invalid string length");
        if (length > input.available()) throw new IllegalArgumentException("String exceeds frame payload");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length <= 0 || value.length > ProtocolLimits.MAX_ITEM_BYTES) throw new IllegalArgumentException("Invalid item length");
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > ProtocolLimits.MAX_ITEM_BYTES) throw new IllegalArgumentException("Invalid item length");
        if (length > input.available()) throw new IllegalArgumentException("Item exceeds frame payload");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void writeResourceBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > ProtocolLimits.MAX_ITEM_BYTES) {
            throw new IllegalArgumentException("Invalid resource length");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readResourceBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > ProtocolLimits.MAX_ITEM_BYTES) {
            throw new IllegalArgumentException("Invalid resource length");
        }
        if (length > input.available()) throw new IllegalArgumentException("Resource exceeds frame payload");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static <T> T cast(Object value, Class<T> type) {
        if (!type.isInstance(value)) throw new IllegalArgumentException("Unexpected message type: " + value.getClass().getSimpleName());
        return type.cast(value);
    }

    private static final class FramePayloadInputStream extends InputStream {
        private final InputStream input;
        private int remaining;

        private FramePayloadInputStream(InputStream input, int remaining) {
            this.input = input;
            this.remaining = remaining;
        }

        int getRemaining() { return remaining; }

        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = input.read();
            if (value < 0) throw new EOFException("Truncated frame payload");
            remaining--;
            return value;
        }

        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (target == null) throw new NullPointerException("target");
            if (offset < 0 || length < 0 || offset > target.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int read = input.read(target, offset, Math.min(length, remaining));
            if (read < 0) throw new EOFException("Truncated frame payload");
            if (read == 0) return 0;
            remaining -= read;
            return read;
        }

        @Override public int available() { return remaining; }
    }
}
