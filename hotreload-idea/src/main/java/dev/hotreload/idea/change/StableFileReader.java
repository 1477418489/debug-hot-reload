package dev.hotreload.idea.change;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

final class StableFileReader {
    private StableFileReader() {
    }

    static byte[] read(Path file, int maxBytes) throws IOException {
        return read(file, maxBytes, false);
    }

    static byte[] read(Path file, int maxBytes, boolean allowEmpty) throws IOException {
        if (file == null) throw new NullPointerException("file");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        Path target = PathSafety.realFile(file);
        BasicFileAttributes before = Files.readAttributes(target, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        long size = before.size();
        if (!before.isRegularFile() || size > maxBytes) {
            throw new IllegalArgumentException("file exceeds the protocol limit");
        }
        if (!allowEmpty && size == 0L) throw new IllegalArgumentException("file must not be empty");
        byte[] content;
        try (InputStream input = Files.newInputStream(target, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            content = readExactly(input, (int) size);
        }
        BasicFileAttributes after = Files.readAttributes(target, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("file changed while it was being read");
        }
        return content;
    }

    static byte[] readExactly(InputStream input, int expectedBytes) throws IOException {
        if (input == null) throw new NullPointerException("input");
        if (expectedBytes < 0) throw new IllegalArgumentException("expectedBytes must not be negative");
        byte[] content = new byte[expectedBytes];
        int offset = 0;
        while (offset < expectedBytes) {
            int read = input.read(content, offset, expectedBytes - offset);
            if (read < 0) throw new IOException("file ended while it was being read");
            if (read == 0) continue;
            offset += read;
        }
        if (input.read() >= 0) throw new IOException("file grew while it was being read");
        return content;
    }
}
