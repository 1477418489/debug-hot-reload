package dev.hotreload.idea.change;

import dev.hotreload.protocol.message.ClassUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassFileBatchCollectorTest {
    @TempDir Path tempDirectory;

    @Test void turnsGeneratedClassPathsIntoOneBoundedBatch() throws Exception {
        Path output = tempDirectory.resolve("classes");
        Path classFile = output.resolve("demo/Changed.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[]{0, 1, 2});
        ClassFileBatchCollector collector = new ClassFileBatchCollector(256, 8 * 1024 * 1024);

        collector.record(output, "demo/Changed.class");
        List<ClassUpdate> updates = collector.finish(true);

        assertEquals(1, updates.size());
        assertEquals("demo.Changed", updates.get(0).getBinaryName());
        assertArrayEquals(new byte[]{0, 1, 2}, updates.get(0).getBytecode());
        assertTrue(collector.finish(true).isEmpty());
    }

    @Test void clearsGeneratedPathsWhenCompilationFails() throws Exception {
        ClassFileBatchCollector collector = new ClassFileBatchCollector(256, 1024);
        collector.record(tempDirectory, "demo/Changed.class");

        assertTrue(collector.finish(false).isEmpty());
        assertTrue(collector.finish(true).isEmpty());
    }

    @Test void rejectsAClassBatchBeyondTheHardLimit() throws Exception {
        ClassFileBatchCollector collector = new ClassFileBatchCollector(2, 1024);
        collector.record(tempDirectory, "demo/One.class");
        collector.record(tempDirectory, "demo/Two.class");
        collector.record(tempDirectory, "demo/Three.class");

        assertThrows(IllegalStateException.class, () -> collector.finish(true));
        assertTrue(collector.finish(true).isEmpty());
    }

    @Test void rejectsAClassBatchThatExceedsTheFrameBudget() throws Exception {
        Path output = tempDirectory.resolve("large-classes");
        Path first = output.resolve("demo/One.class");
        Path second = output.resolve("demo/Two.class");
        Files.createDirectories(first.getParent());
        Files.write(first, new byte[4096]);
        Files.write(second, new byte[4096]);
        ClassFileBatchCollector collector = new ClassFileBatchCollector(2, 4096, 40_000);

        collector.record(output, "demo/One.class");
        collector.record(output, "demo/Two.class");

        assertThrows(IllegalStateException.class, () -> collector.finish(true));
        assertTrue(collector.finish(true).isEmpty());
    }

    @Test void reportsARejectedBatchEvenWhenNoClassWasAccepted() throws Exception {
        ClassFileBatchCollector collector = new ClassFileBatchCollector(2, 1024);
        collector.record(tempDirectory, "../outside.class");

        assertThrows(IllegalStateException.class, () -> collector.finish(true));
        assertTrue(collector.finish(true).isEmpty());
    }

    @Test void acceptsSupplementaryUnicodeCodePointsInJavaClassNames() throws Exception {
        String supplementaryLetter = new String(Character.toChars(0x10400));
        Path output = tempDirectory.resolve("unicode-classes");
        Path classFile = output.resolve("demo").resolve(supplementaryLetter + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[]{1});
        ClassFileBatchCollector collector = new ClassFileBatchCollector(2, 1024);

        collector.record(output, "demo/" + supplementaryLetter + ".class");
        List<ClassUpdate> updates = collector.finish(true);

        assertEquals(1, updates.size());
        assertEquals("demo." + supplementaryLetter, updates.get(0).getBinaryName());
    }
}
