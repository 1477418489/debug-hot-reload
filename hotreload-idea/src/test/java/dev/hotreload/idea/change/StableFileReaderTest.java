package dev.hotreload.idea.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StableFileReaderTest {
    @TempDir Path tempDirectory;

    @Test void readsExactlyTheExpectedNumberOfBytes() throws Exception {
        assertArrayEquals(new byte[]{1, 2},
                StableFileReader.readExactly(new ByteArrayInputStream(new byte[]{1, 2}), 2));
    }

    @Test void rejectsAFileThatGrowsPastTheExpectedLength() {
        assertThrows(IOException.class, () -> StableFileReader.readExactly(
                new ByteArrayInputStream(new byte[]{1, 2}), 1));
    }

    @Test void allowsEmptyFilesOnlyWhenExplicitlyRequested() throws Exception {
        Path empty = Files.createFile(tempDirectory.resolve("empty.css"));

        assertThrows(IllegalArgumentException.class, () -> StableFileReader.read(empty, 1024));
        assertEquals(0, StableFileReader.read(empty, 1024, true).length);
    }
}
