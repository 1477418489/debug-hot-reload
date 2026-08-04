package dev.hotreload.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

final class BootstrapInstaller {
    private static final String RESOURCE = "/bootstrap/hotreload-bootstrap.jar";
    private static final String EXTRACTED_NAME = "hotreload-bootstrap.jar";

    private BootstrapInstaller() {
    }

    static Installation install(Instrumentation instrumentation, Path launchDirectory) throws IOException {
        if (instrumentation == null) throw new NullPointerException("instrumentation");
        if (launchDirectory == null || !launchDirectory.isAbsolute()
                || hasSymbolicComponent(launchDirectory)
                || !Files.isDirectory(launchDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("launchDirectory must be an existing absolute directory");
        }
        Path normalized = launchDirectory.normalize();
        if (!normalized.equals(launchDirectory)) {
            throw new IllegalArgumentException("launchDirectory must be normalized");
        }
        Path extracted = normalized.resolve(EXTRACTED_NAME);
        try (InputStream input = BootstrapInstaller.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Embedded bootstrap JAR is missing");
            Files.copy(input, extracted);
        }

        JarFile jarFile = null;
        try {
            jarFile = new JarFile(extracted.toFile(), false);
            if (jarFile.getJarEntry("dev/hotreload/bootstrap/HotReloadBridge.class") == null) {
                throw new IOException("Embedded bootstrap JAR is invalid");
            }
            instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
            return new Installation(extracted, jarFile);
        } catch (RuntimeException e) {
            closeAndDelete(jarFile, extracted);
            throw e;
        } catch (IOException e) {
            closeAndDelete(jarFile, extracted);
            throw e;
        }
    }

    private static void closeAndDelete(JarFile jarFile, Path extracted) {
        if (jarFile != null) {
            try { jarFile.close(); } catch (IOException ignored) { }
        }
        try { Files.deleteIfExists(extracted); } catch (IOException ignored) { }
    }

    private static boolean hasSymbolicComponent(Path path) {
        Path root = path.getRoot();
        Path current = root;
        for (Path component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    static final class Installation implements AutoCloseable {
        private final Path extractedPath;
        private final JarFile jarFile;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Installation(Path extractedPath, JarFile jarFile) {
            this.extractedPath = extractedPath;
            this.jarFile = jarFile;
        }

        Path getExtractedPath() { return extractedPath; }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            IOException failure = null;
            try {
                jarFile.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                Files.deleteIfExists(extractedPath);
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
            if (failure != null) throw failure;
        }
    }
}
