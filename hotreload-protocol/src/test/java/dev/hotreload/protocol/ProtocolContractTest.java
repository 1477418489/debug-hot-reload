package dev.hotreload.protocol;

import dev.hotreload.protocol.io.FrameCodec;
import dev.hotreload.protocol.message.*;
import dev.hotreload.protocol.resource.ResourceId;
import dev.hotreload.protocol.session.SessionDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolContractTest {
    @Test void helloRoundTrip() throws Exception {
        HelloRequest request = new HelloRequest("r1", "token", "launch");
        assertEquals(request, FrameCodec.decodeHello(FrameCodec.encode(request)));
        HelloResponse response = new HelloResponse("r1", 1, true, "21.0.11", 1);
        assertEquals(response, FrameCodec.decodeHelloResponse(FrameCodec.encode(response)));
        assertFalse(FrameCodec.decodeHelloResponse(FrameCodec.encode(response)).isEnhancedRedefineSupported());
        HelloResponse enhanced = new HelloResponse("r2", 1, true, true, "1.8.0_172", 1);
        assertEquals(enhanced, FrameCodec.decodeHelloResponse(FrameCodec.encode(enhanced)));
        assertTrue(FrameCodec.decodeHelloResponse(FrameCodec.encode(enhanced)).isEnhancedRedefineSupported());
    }

    @Test void mapperAndClassRoundTrip() throws Exception {
        MapperUpdate mapper = new MapperUpdate("mapper.xml", sha256(), new byte[]{1, 2});
        MapperReloadRequest mr = new MapperReloadRequest("r", "t", mapper);
        assertEquals(mr, FrameCodec.decodeMapper(FrameCodec.encode(mr)));
        ClassUpdate c = new ClassUpdate("a.B", new byte[]{3, 4});
        ClassReloadRequest cr = new ClassReloadRequest("r", "t", Collections.singletonList(c));
        assertEquals(cr, FrameCodec.decodeClasses(FrameCodec.encode(cr)));
    }

    @Test void reloadRequestsExposeAuthenticationMetadata() {
        List<ReloadRequest> requests = Arrays.<ReloadRequest>asList(
                new MapperReloadRequest("mapper", "token",
                        new MapperUpdate("mapper/Demo.xml", sha256(), new byte[]{1})),
                new ClassReloadRequest("class", "token",
                        Collections.singletonList(new ClassUpdate("demo.Type", new byte[]{1}))),
                new ResourceReloadRequest("resource", "token", "static/app.js",
                        new byte[]{1}, "javascript"));

        assertEquals(Arrays.asList("mapper", "class", "resource"), Arrays.asList(
                requests.get(0).getRequestId(), requests.get(1).getRequestId(),
                requests.get(2).getRequestId()));
        for (ReloadRequest request : requests) assertEquals("token", request.getToken());
    }

    @Test void rejectedResourceFrameRetainsItsRequestId() throws Exception {
        ResourceReloadRequest request = new ResourceReloadRequest(
                "resource", "token", "static/app.js", new byte[]{1}, "javascript");
        ByteArrayInputStream encoded = new ByteArrayInputStream(FrameCodec.encode(request));
        int payloadLength = new DataInputStream(encoded).readInt();

        assertEquals("resource", FrameCodec.discardAndReadRequestId(encoded, payloadLength));
        assertEquals(-1, encoded.read());
    }

    @Test void emptyResourceContentRoundTripsWithoutRelaxingOtherPayloads() {
        ResourceReloadRequest request = new ResourceReloadRequest(
                "empty", "token", "static/empty.css", new byte[0], "css");

        ResourceReloadRequest decoded = (ResourceReloadRequest) FrameCodec.decode(FrameCodec.encode(request));

        assertEquals(request, decoded);
        assertEquals(0, decoded.getContentLength());
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceReloadRequest("null", "token", "static/app.css", null, "css"));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassUpdate("demo.Empty", new byte[0]));
    }

    @Test void resourceRequestsRejectUnsafeOrOversizedFieldsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceReloadRequest(
                "unsafe", "token", "../app.css", new byte[0], "css"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceReloadRequest(
                "large", "token", "static/app.css",
                new byte[ProtocolLimits.MAX_ITEM_BYTES + 1], "css"));
        assertEquals("properties", new ResourceReloadRequest(
                "type", "token", "application.yml", new byte[0], "").getContentType());
    }

    @Test void writesAClassBatchWithoutBufferingTheWholeFrame() throws Exception {
        List<ClassUpdate> updates = Arrays.asList(
                new ClassUpdate("demo.First", new byte[1024]),
                new ClassUpdate("demo.Second", new byte[1024]));
        ClassReloadRequest request = new ClassReloadRequest("request", "token", updates);
        ChunkLimitedOutputStream output = new ChunkLimitedOutputStream(1024);

        FrameCodec.write(output, request);

        assertEquals(request, FrameCodec.decodeClasses(output.toByteArray()));
    }

    @Test void responseRoundTripAndExplicitErrorIds() throws Exception {
        ReloadItemResult item = new ReloadItemResult("mapper.xml", OperationStatus.SUCCESS, null, "ok", "");
        ReloadResponse response = new ReloadResponse("r", OperationStatus.FAILED,
                ReloadErrorCode.AUTHENTICATION_FAILED, "bad", Collections.singletonList(item));
        assertEquals(response, FrameCodec.decodeResponse(FrameCodec.encode(response)));
        assertEquals(1, ReloadErrorCode.AUTHENTICATION_FAILED.wireId());
        assertNotEquals(ReloadErrorCode.AUTHENTICATION_FAILED.wireId(), ReloadErrorCode.INTERNAL_ERROR.wireId());
    }

    @Test void rejectsInvalidTokenAndMalformedFrames() {
        assertThrows(IllegalArgumentException.class, () -> new HelloRequest("r", "", "l"));
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.decodeHello(new byte[]{0, 0, 0, 3, 1}));
        byte[] encoded = FrameCodec.encode(new HelloRequest("r", "t", "l"));
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.decodeHello(Arrays.copyOf(encoded, encoded.length - 1)));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.decodeHello(trailing));
        byte[] overLimitHeader = new byte[]{0x02, 0x00, 0x00, 0x01};
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.decodeHello(overLimitHeader));
        byte[] unknownType = new byte[]{0, 0, 0, 1, 127};
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.decode(unknownType));
        byte[] unknownStatus = FrameCodec.encode(new ReloadResponse("r", OperationStatus.SUCCESS, null, "", Collections.<ReloadItemResult>emptyList()));
        unknownStatus[10] = 0;
        unknownStatus[11] = 0;
        unknownStatus[12] = 0;
        unknownStatus[13] = 99;
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.decodeResponse(unknownStatus));
    }

    @Test void appliesTheHelloFrameLimitBeforeAllocatingThePayload() {
        int declared = ProtocolLimits.MAX_HELLO_FRAME_BYTES + 1;
        byte[] header = new byte[]{(byte) (declared >>> 24), (byte) (declared >>> 16),
                (byte) (declared >>> 8), (byte) declared};
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.read(
                new ByteArrayInputStream(header), ProtocolLimits.MAX_HELLO_FRAME_BYTES));
    }

    @Test void authenticatesSessionDescriptorsWithoutPersistingTheToken() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        Path directory = Files.createTempDirectory("authenticated-session");
        Path target = directory.resolve("session.properties");

        SessionDescriptor.authenticated("launch", 1, 1234, key).writeAtomically(target);
        SessionDescriptor read = SessionDescriptor.read(target);

        assertTrue(read.verifies(token));
        assertFalse(read.verifies(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32])));
        assertFalse(new String(Files.readAllBytes(target), "UTF-8").contains(token));
    }

    @Test void enforcesPayloadAndBatchLimits() {
        assertThrows(IllegalArgumentException.class, () -> new MapperUpdate("x", new byte[31], new byte[]{1}));
        ClassUpdate update = new ClassUpdate("A", new byte[]{1});
        List<ClassUpdate> many = new ArrayList<ClassUpdate>();
        for (int i = 0; i < 257; i++) many.add(update);
        assertThrows(IllegalArgumentException.class, () -> new ClassReloadRequest("r", "t", many));
        List<ReloadItemResult> results = new ArrayList<ReloadItemResult>();
        ReloadItemResult item = new ReloadItemResult("x", OperationStatus.SUCCESS, null, "", "");
        for (int i = 0; i < 257; i++) results.add(item);
        assertThrows(IllegalArgumentException.class, () -> new ReloadResponse("r", OperationStatus.SUCCESS, null, "", results));
        List<ClassUpdate> aggregate = new ArrayList<ClassUpdate>();
        for (int i = 0; i < 4; i++) aggregate.add(new ClassUpdate("A" + i, new byte[8 * 1024 * 1024]));
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(new ClassReloadRequest("r", "t", aggregate)));
    }

    @Test void payloadArraysAreDefensivelyCopied() {
        byte[] digest = sha256();
        byte[] content = new byte[]{1, 2};
        MapperUpdate update = new MapperUpdate("x.xml", digest, content);
        digest[0] = 9;
        content[0] = 9;
        assertEquals(0, update.getSha256()[0]);
        assertEquals(1, update.getContent()[0]);
        byte[] returned = update.getContent();
        returned[0] = 8;
        assertEquals(1, update.getContent()[0]);
    }

    @Test void resourceIdNormalizesAndRejectsUnsafePaths() {
        assertEquals("mappers/User.xml", ResourceId.of("mappers\\User.xml").value());
        for (String bad : new String[]{"", "/abs", "C:/x", "http://x", "a//b", "a/./b", "a/../b"}) {
            assertThrows(IllegalArgumentException.class, () -> ResourceId.of(bad));
        }
    }

    @Test void sessionDescriptorOmitsSecretsAndWritesAtomically() throws Exception {
        Path dir = Files.createTempDirectory("session");
        Path target = dir.resolve("session.json");
        SessionDescriptor descriptor = new SessionDescriptor("launch", 1, 1234);
        descriptor.writeAtomically(target);
        String text = new String(Files.readAllBytes(target), "UTF-8");
        assertTrue(text.contains("127.0.0.1"));
        assertFalse(text.contains("token"));
        assertFalse(descriptor.toString().contains("1234"));
    }

    @Test void rejectsAnOversizedSessionDescriptorBeforeParsingProperties() throws Exception {
        Path dir = Files.createTempDirectory("oversized-session");
        Path target = dir.resolve("session.properties");
        byte[] comment = new byte[70 * 1024];
        java.util.Arrays.fill(comment, (byte) 'x');
        comment[0] = '#';
        String properties = "\nlaunchId=launch\nprotocol=1\naddress=127.0.0.1\nport=1234\n";
        java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
        content.write(comment);
        content.write(properties.getBytes("UTF-8"));
        Files.write(target, content.toByteArray(), StandardOpenOption.CREATE_NEW);

        assertThrows(java.io.IOException.class, () -> SessionDescriptor.read(target));
    }

    private static byte[] sha256() {
        return new byte[32];
    }

    private static final class ChunkLimitedOutputStream extends ByteArrayOutputStream {
        private final int maxChunkBytes;

        private ChunkLimitedOutputStream(int maxChunkBytes) {
            this.maxChunkBytes = maxChunkBytes;
        }

        @Override public synchronized void write(byte[] value, int offset, int length) {
            if (length > maxChunkBytes) {
                throw new IllegalStateException("whole frame was buffered before writing");
            }
            super.write(value, offset, length);
        }
    }
}
