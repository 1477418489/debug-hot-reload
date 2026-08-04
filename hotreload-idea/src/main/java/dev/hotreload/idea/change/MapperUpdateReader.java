package dev.hotreload.idea.change;

import dev.hotreload.protocol.ProtocolLimits;
import dev.hotreload.protocol.message.MapperUpdate;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class MapperUpdateReader {
    private MapperUpdateReader() {
    }

    public static MapperUpdate read(Path sourceRoot, Path file) throws IOException {
        if (sourceRoot == null || file == null) throw new NullPointerException("sourceRoot and file are required");
        Path root = PathSafety.realDirectory(sourceRoot);
        Path target;
        try {
            target = PathSafety.containedFile(root, file);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Mapper XML path is not a safe regular file", e);
        }

        byte[] content = StableFileReader.read(target, ProtocolLimits.MAX_ITEM_BYTES);
        verifyUtf8(content);
        String resourceId = root.relativize(target).toString().replace(File.separatorChar, '/');
        return new MapperUpdate(resourceId, sha256(content), content);
    }

    private static void verifyUtf8(byte[] content) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Mapper XML is not valid UTF-8", e);
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
