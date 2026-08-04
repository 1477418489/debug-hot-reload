package dev.hotreload.idea.change;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StaticResourceSynchronizerTest {
    @TempDir Path tempDirectory;

    @Test void createsMissingOutputDirectoriesAndInstallsTheResource() throws Exception {
        Path sourceRoot = directory("src-main-resources");
        Path outputRoot = directory("app-classes");
        Path source = write(sourceRoot.resolve("static/assets/app.css"), "body{}".getBytes(StandardCharsets.UTF_8));

        StaticResourceSynchronizer.Result result = StaticResourceSynchronizer.synchronize(
                sourceRoot, outputRoot, source);

        Path target = outputRoot.resolve("static/assets/app.css");
        assertEquals("static/assets/app.css", result.getResourceId());
        assertEquals(target.toRealPath(), result.getTarget());
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(target));
        assertArrayEquals(Files.readAllBytes(source), result.getContent());
    }

    @Test void replacesAnExistingOutputResource() throws Exception {
        Path sourceRoot = directory("src-main-resources");
        Path outputRoot = directory("app-classes");
        Path source = write(sourceRoot.resolve("static/page.html"), bytes("new"));
        Path target = write(outputRoot.resolve("static/page.html"), bytes("old"));

        StaticResourceSynchronizer.synchronize(sourceRoot, outputRoot, source);

        assertArrayEquals(bytes("new"), Files.readAllBytes(target));
    }

    @Test void installsAnEmptyStaticResource() throws Exception {
        Path sourceRoot = directory("src-main-resources");
        Path outputRoot = directory("app-classes");
        Path source = write(sourceRoot.resolve("static/empty.js"), new byte[0]);

        StaticResourceSynchronizer.Result result = StaticResourceSynchronizer.synchronize(
                sourceRoot, outputRoot, source);

        assertEquals(0, result.getContentLength());
        assertEquals(0L, Files.size(outputRoot.resolve("static/empty.js")));
    }

    @Test void rejectsASourceFileOutsideItsResourceRoot() throws Exception {
        Path sourceRoot = directory("src-main-resources");
        Path outputRoot = directory("app-classes");
        Path outside = write(tempDirectory.resolve("outside/app.css"), bytes("outside"));

        assertThrows(IllegalArgumentException.class, () -> StaticResourceSynchronizer.synchronize(
                sourceRoot, outputRoot, outside));
    }

    @Test void rejectsAFileThatIsNotAStaticResource() throws Exception {
        Path sourceRoot = directory("src-main-resources");
        Path outputRoot = directory("app-classes");
        Path source = write(sourceRoot.resolve("data/payload.bin"), new byte[]{1});

        assertThrows(IllegalArgumentException.class, () -> StaticResourceSynchronizer.synchronize(
                sourceRoot, outputRoot, source));
    }

    @Test void rejectsAResourceAboveTheStaticUpdateLimit() throws Exception {
        Path sourceRoot = directory("src-main-resources");
        Path outputRoot = directory("app-classes");
        Path source = write(sourceRoot.resolve("static/large.js"),
                new byte[StaticResourceSynchronizer.MAX_RESOURCE_BYTES + 1]);

        assertThrows(IllegalArgumentException.class, () -> StaticResourceSynchronizer.synchronize(
                sourceRoot, outputRoot, source));
    }

    @Test void rejectsSymbolicComponentsInTheOutputPathWhenSupported() throws Exception {
        Path sourceRoot = directory("src-main-resources");
        Path outputRoot = directory("app-classes");
        Path source = write(sourceRoot.resolve("static/app.css"), bytes("content"));
        Path outside = directory("outside-output");
        try {
            Files.createSymbolicLink(outputRoot.resolve("static"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.assumeTrue(false,
                    "symbolic links are unavailable: " + unsupported.getClass().getSimpleName());
        }

        assertThrows(IOException.class, () -> StaticResourceSynchronizer.synchronize(
                sourceRoot, outputRoot, source));
    }

    @Test void removesAnInstalledStaticResource() throws Exception {
        Path outputRoot = directory("app-classes");
        Path target = write(outputRoot.resolve("static/app.css"), bytes("old"));

        StaticResourceSynchronizer.RemovalResult result =
                StaticResourceSynchronizer.remove(outputRoot, "static/app.css");

        assertEquals("static/app.css", result.getResourceId());
        assertEquals(target.toAbsolutePath().normalize(), result.getTarget());
        assertTrue(result.wasRemoved());
        assertFalse(Files.exists(target));
    }

    @Test void treatsAnAlreadyMissingStaticResourceAsRemoved() throws Exception {
        Path outputRoot = directory("app-classes");

        StaticResourceSynchronizer.RemovalResult result =
                StaticResourceSynchronizer.remove(outputRoot, "static/missing.css");

        assertFalse(result.wasRemoved());
        assertEquals(outputRoot.resolve("static/missing.css").toAbsolutePath().normalize(),
                result.getTarget());
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectories(tempDirectory.resolve(name));
    }

    private static Path write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, content);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
