package dev.hotreload.idea.change;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Canonical path checks shared by file-backed reload inputs. */
public final class PathSafety {
    private PathSafety() {
    }

    public static Path realDirectory(Path directory) throws IOException {
        if (directory == null) throw new NullPointerException("directory");
        Path absolute = directory.toAbsolutePath().normalize();
        rejectSymbolicComponents(absolute);
        Path real = absolute.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Path is not a directory");
        }
        return real;
    }

    public static Path realFile(Path file) throws IOException {
        if (file == null) throw new NullPointerException("file");
        Path absolute = file.toAbsolutePath().normalize();
        rejectSymbolicComponents(absolute);
        Path real = absolute.toRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Path is not a regular file");
        }
        return real;
    }

    public static Path containedFile(Path root, Path file) throws IOException {
        Path realRoot = realDirectory(root);
        Path realFile = realFile(file);
        if (realFile.equals(realRoot) || !realFile.startsWith(realRoot)) {
            throw new IllegalArgumentException("File is outside its root");
        }
        return realFile;
    }

    public static Path resolveContained(Path root, Path relative, boolean requireFile) throws IOException {
        if (relative == null || relative.isAbsolute()) {
            throw new IllegalArgumentException("Relative path is required");
        }
        Path realRoot = realDirectory(root);
        Path target = realRoot.resolve(relative).normalize();
        if (target.equals(realRoot) || !target.startsWith(realRoot)) {
            throw new IllegalArgumentException("Resolved path is outside its root");
        }
        rejectSymbolicComponents(target);
        if (!requireFile && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target;
        Path realTarget = realFile(target);
        if (!realTarget.startsWith(realRoot)) {
            throw new IllegalArgumentException("Resolved file is outside its root");
        }
        return realTarget;
    }

    public static Path schedulingDirectory(Path directory) throws IOException {
        if (directory == null) throw new NullPointerException("directory");
        Path absolute = directory.toAbsolutePath().normalize();
        rejectSymbolicComponents(absolute);
        return Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
                ? realDirectory(absolute) : absolute;
    }

    public static Path schedulingFile(Path root, Path file) throws IOException {
        Path safeRoot = schedulingDirectory(root);
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.equals(safeRoot) || !absolute.startsWith(safeRoot)) {
            throw new IllegalArgumentException("File is outside its root");
        }
        rejectSymbolicComponents(absolute);
        return Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) ? realFile(absolute) : absolute;
    }

    private static void rejectSymbolicComponents(Path path) throws IOException {
        Path root = path.getRoot();
        Path current = root;
        for (Path component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic paths are not reload inputs");
            }
        }
    }
}
