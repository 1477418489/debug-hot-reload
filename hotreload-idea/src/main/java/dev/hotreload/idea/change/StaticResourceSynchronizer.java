package dev.hotreload.idea.change;

import dev.hotreload.protocol.resource.ResourceId;
import dev.hotreload.protocol.util.ResourceTypeDetector;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Installs a changed source resource into one verified compiler output root. */
public final class StaticResourceSynchronizer {
    public static final int MAX_RESOURCE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_INSTALL_ATTEMPTS = 3;
    private static final int MAX_REMOVE_ATTEMPTS = 3;
    private static final Object INSTALL_MONITOR = new Object();

    private StaticResourceSynchronizer() {
    }

    public static Result synchronize(Path sourceRoot, Path outputRoot, Path sourceFile)
            throws IOException {
        synchronized (INSTALL_MONITOR) {
            return synchronizeLocked(sourceRoot, outputRoot, sourceFile);
        }
    }

    private static Result synchronizeLocked(Path sourceRoot, Path outputRoot, Path sourceFile)
            throws IOException {
        if (sourceRoot == null || outputRoot == null || sourceFile == null) {
            throw new NullPointerException("sourceRoot, outputRoot and sourceFile are required");
        }

        Path safeSourceRoot = PathSafety.realDirectory(sourceRoot);
        Path safeOutputRoot = PathSafety.realDirectory(outputRoot);
        rejectOverlappingRoots(safeSourceRoot, safeOutputRoot);

        String resourceId = resourceId(safeSourceRoot, sourceFile);
        Path resourcePath = Paths.get(resourceId.replace('/', File.separatorChar));
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_INSTALL_ATTEMPTS; attempt++) {
            try {
                return installOnce(safeSourceRoot, safeOutputRoot, sourceFile,
                        resourceId, resourcePath);
            } catch (IOException failure) {
                lastFailure = failure;
                if (attempt < MAX_INSTALL_ATTEMPTS) pauseBeforeRetry(attempt);
            }
        }
        throw lastFailure == null ? new IOException("resource installation failed") : lastFailure;
    }

    private static Result installOnce(Path sourceRoot, Path outputRoot, Path sourceFile,
                                      String resourceId, Path resourcePath) throws IOException {
        Path safeSourceFile = PathSafety.containedFile(sourceRoot, sourceFile);
        byte[] content = StableFileReader.read(safeSourceFile, MAX_RESOURCE_BYTES, true);
        Path target = prepareTarget(outputRoot, resourcePath);
        Path safeParent = PathSafety.realDirectory(target.getParent());
        Path temporary = Files.createTempFile(safeParent,
                "." + target.getFileName() + ".hotreload-", ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            byte[] staged = StableFileReader.read(temporary, MAX_RESOURCE_BYTES, true);
            if (!Arrays.equals(content, staged)) {
                throw new IOException("staged resource verification failed");
            }

            replace(temporary, target);
            Path installedTarget = PathSafety.resolveContained(outputRoot, resourcePath, true);
            byte[] installedContent = StableFileReader.read(installedTarget,
                    MAX_RESOURCE_BYTES, true);
            if (!Arrays.equals(content, installedContent)) {
                throw new IOException("installed resource verification failed");
            }
            return new Result(resourceId, installedTarget, content);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path prepareTarget(Path outputRoot, Path resourcePath) throws IOException {
        Path target = PathSafety.resolveContained(outputRoot, resourcePath, false);
        Path parent = target.getParent();
        if (parent == null) throw new IllegalArgumentException("resource target has no parent");
        Files.createDirectories(parent);
        Path safeParent = PathSafety.realDirectory(parent);
        if (!safeParent.startsWith(outputRoot)) {
            throw new IllegalArgumentException("resource target parent is outside the output root");
        }
        return PathSafety.resolveContained(outputRoot, resourcePath, false);
    }

    public static String resourceId(Path sourceRoot, Path sourceFile) throws IOException {
        if (sourceRoot == null || sourceFile == null) {
            throw new NullPointerException("sourceRoot and sourceFile are required");
        }
        Path safeSourceRoot = PathSafety.realDirectory(sourceRoot);
        Path safeSourceFile = PathSafety.schedulingFile(safeSourceRoot, sourceFile);
        String relative = safeSourceRoot.relativize(safeSourceFile).toString()
                .replace(File.separatorChar, '/');
        String resourceId = ResourceId.of(relative).value();
        if (!ResourceTypeDetector.isStaticResource(resourceId)) {
            throw new IllegalArgumentException("file is not a supported static resource");
        }
        return resourceId;
    }

    public static RemovalResult remove(Path outputRoot, String resourceId) throws IOException {
        synchronized (INSTALL_MONITOR) {
            if (outputRoot == null || resourceId == null) {
                throw new NullPointerException("outputRoot and resourceId are required");
            }
            Path safeOutputRoot = PathSafety.realDirectory(outputRoot);
            String normalizedId = ResourceId.of(resourceId).value();
            if (!ResourceTypeDetector.isStaticResource(normalizedId)) {
                throw new IllegalArgumentException("file is not a supported static resource");
            }
            Path relative = Paths.get(normalizedId.replace('/', File.separatorChar));
            Path target = PathSafety.resolveContained(safeOutputRoot, relative, false);
            boolean removed = false;
            IOException lastFailure = null;
            for (int attempt = 1; attempt <= MAX_REMOVE_ATTEMPTS; attempt++) {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return new RemovalResult(normalizedId, target, removed);
                }
                try {
                    Path safeTarget = PathSafety.resolveContained(
                            safeOutputRoot, relative, true);
                    removed |= Files.deleteIfExists(safeTarget);
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        return new RemovalResult(normalizedId, target, removed);
                    }
                    lastFailure = new IOException("removed resource reappeared");
                } catch (IOException failure) {
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        return new RemovalResult(normalizedId, target, removed);
                    }
                    lastFailure = failure;
                }
                if (attempt < MAX_REMOVE_ATTEMPTS) pauseBeforeRetry(attempt);
            }
            throw lastFailure == null ? new IOException("resource removal failed") : lastFailure;
        }
    }

    private static void pauseBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(10L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("resource synchronization interrupted", interrupted);
        }
    }

    private static void rejectOverlappingRoots(Path sourceRoot, Path outputRoot) {
        if (sourceRoot.equals(outputRoot) || sourceRoot.startsWith(outputRoot)
                || outputRoot.startsWith(sourceRoot)) {
            throw new IllegalArgumentException("source and output roots must not overlap");
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class Result {
        private final String resourceId;
        private final Path target;
        private final byte[] content;

        private Result(String resourceId, Path target, byte[] content) {
            this.resourceId = resourceId;
            this.target = target;
            this.content = content.clone();
        }

        public String getResourceId() { return resourceId; }
        public Path getTarget() { return target; }
        public byte[] getContent() { return content.clone(); }
        public int getContentLength() { return content.length; }
    }

    public static final class RemovalResult {
        private final String resourceId;
        private final Path target;
        private final boolean removed;

        private RemovalResult(String resourceId, Path target, boolean removed) {
            this.resourceId = resourceId;
            this.target = target;
            this.removed = removed;
        }

        public String getResourceId() { return resourceId; }
        public Path getTarget() { return target; }
        public boolean wasRemoved() { return removed; }
    }
}
