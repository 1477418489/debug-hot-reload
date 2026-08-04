package dev.hotreload.idea.change;

import dev.hotreload.protocol.message.MapperUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MapperUpdateReaderTest {
    @TempDir Path tempDirectory;

    @Test void readsClasspathRelativeUtf8XmlAndComputesSha256() throws Exception {
        Path root = tempDirectory.resolve("resources");
        Path file = root.resolve("mapper/UserMapper.xml");
        Files.createDirectories(file.getParent());
        byte[] content = "<mapper namespace=\"demo.UserMapper\"/>".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        MapperUpdate update = MapperUpdateReader.read(root, file);

        assertEquals("mapper/UserMapper.xml", update.getResourceId());
        assertArrayEquals(content, update.getContent());
        assertEquals(32, update.getSha256().length);
    }

    @Test void rejectsFilesOutsideTheSourceRoot() throws Exception {
        Path root = tempDirectory.resolve("resources");
        Path file = tempDirectory.resolve("other.xml");
        Files.createDirectories(root);
        Files.write(file, new byte[]{'<'});

        assertThrows(IllegalArgumentException.class, () -> MapperUpdateReader.read(root, file));
    }

    @Test void rejectsInvalidUtf8() throws Exception {
        Path root = tempDirectory.resolve("resources");
        Path file = root.resolve("mapper.xml");
        Files.createDirectories(root);
        Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28});

        assertThrows(IllegalArgumentException.class, () -> MapperUpdateReader.read(root, file));
    }

    @Test void rejectsASymbolicFileEvenWhenItsLexicalPathIsInsideTheRoot() throws Exception {
        Path root = tempDirectory.resolve("resources");
        Path outside = tempDirectory.resolve("outside.xml");
        Files.createDirectories(root);
        Files.write(outside, "<mapper/>".getBytes(StandardCharsets.UTF_8));
        Path link = root.resolve("linked.xml");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception unsupported) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + unsupported.getClass().getSimpleName());
        }

        assertThrows(java.io.IOException.class, () -> MapperUpdateReader.read(root, link));
    }
}
