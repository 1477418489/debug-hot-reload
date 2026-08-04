package dev.hotreload.idea.change;

import dev.hotreload.protocol.ProtocolLimits;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.resource.ResourceId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ClassFileBatchCollector {
    private static final long CLASS_REQUEST_FIXED_BYTES = 1L + (3L * Integer.BYTES)
            + (2L * ProtocolLimits.MAX_STRING_BYTES);

    private final int maxClasses;
    private final int maxClassBytes;
    private final int maxFrameBytes;
    private final Map<String, Path> generatedClasses = new LinkedHashMap<String, Path>();
    private String rejection;

    ClassFileBatchCollector(int maxClasses, int maxClassBytes) {
        this(maxClasses, maxClassBytes, ProtocolLimits.MAX_FRAME_BYTES);
    }

    ClassFileBatchCollector(int maxClasses, int maxClassBytes, int maxFrameBytes) {
        if (maxClasses <= 0 || maxClassBytes <= 0 || maxFrameBytes <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        if (maxFrameBytes > ProtocolLimits.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("frame limit exceeds protocol limit");
        }
        this.maxClasses = maxClasses;
        this.maxClassBytes = maxClassBytes;
        this.maxFrameBytes = maxFrameBytes;
    }

    synchronized void record(Path outputRoot, String relativePath) {
        if (outputRoot == null || relativePath == null) throw new NullPointerException("class path is required");
        if (rejection != null) return;
        String resourcePath;
        try {
            resourcePath = ResourceId.of(relativePath.replace('\\', '/')).value();
        } catch (IllegalArgumentException e) {
            rejection = "invalid generated class path";
            return;
        }
        if (!resourcePath.endsWith(".class")) return;
        String binaryName = resourcePath.substring(0, resourcePath.length() - ".class".length())
                .replace('/', '.');
        if (!isBinaryName(binaryName)) return;

        Path root;
        Path target;
        try {
            root = PathSafety.realDirectory(outputRoot);
            target = PathSafety.resolveContained(root,
                    Paths.get(resourcePath.replace('/', java.io.File.separatorChar)), false);
        } catch (IOException | IllegalArgumentException e) {
            rejection = "generated class is outside output root";
            return;
        }
        Path existing = generatedClasses.get(binaryName);
        if (existing == null && generatedClasses.size() >= maxClasses) {
            rejection = "class batch exceeds protocol limit";
            return;
        }
        Path previous = generatedClasses.put(binaryName, target);
        if (previous != null && !previous.equals(target)) rejection = "duplicate generated binary name";
    }

    synchronized List<ClassUpdate> finish(boolean successful) throws IOException {
        Map<String, Path> snapshot = new LinkedHashMap<String, Path>(generatedClasses);
        String failure = rejection;
        generatedClasses.clear();
        rejection = null;
        if (!successful) return new ArrayList<ClassUpdate>();
        if (failure != null) throw new IllegalStateException(failure);
        if (snapshot.isEmpty()) return new ArrayList<ClassUpdate>();

        validateBatch(snapshot);

        List<ClassUpdate> updates = new ArrayList<ClassUpdate>(snapshot.size());
        long encodedBytes = CLASS_REQUEST_FIXED_BYTES;
        for (Map.Entry<String, Path> entry : snapshot.entrySet()) {
            int nameBytes = entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            long itemPrefix = (2L * Integer.BYTES) + nameBytes;
            long remaining = (long) maxFrameBytes - encodedBytes - itemPrefix;
            if (remaining <= 0) throw new IllegalStateException("class batch exceeds frame limit");
            int maxReadable = (int) Math.min((long) maxClassBytes, remaining);
            byte[] bytecode = StableFileReader.read(entry.getValue(), maxReadable);
            updates.add(new ClassUpdate(entry.getKey(), bytecode));
            encodedBytes += itemPrefix + bytecode.length;
        }
        return updates;
    }

    private void validateBatch(Map<String, Path> snapshot) throws IOException {
        long encodedBytes = CLASS_REQUEST_FIXED_BYTES;
        for (Map.Entry<String, Path> entry : snapshot.entrySet()) {
            BasicFileAttributes attributes = Files.readAttributes(entry.getValue(),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IllegalArgumentException("class file must be a regular file");
            }
            long classBytes = attributes.size();
            if (classBytes <= 0) throw new IllegalArgumentException("class file must not be empty");
            if (classBytes > maxClassBytes) throw new IllegalArgumentException("class file exceeds protocol limit");

            int nameBytes = entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            long updateBytes = (2L * Integer.BYTES) + nameBytes + classBytes;
            if (updateBytes > (long) maxFrameBytes - encodedBytes) {
                throw new IllegalStateException("class batch exceeds frame limit");
            }
            encodedBytes += updateBytes;
        }
    }

    private static boolean isBinaryName(String value) {
        if (value.isEmpty()) return false;
        String[] segments = value.split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) return false;
            int offset = 0;
            int codePoint = segment.codePointAt(offset);
            if (!Character.isJavaIdentifierStart(codePoint)) return false;
            offset += Character.charCount(codePoint);
            while (offset < segment.length()) {
                codePoint = segment.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(codePoint)) return false;
                offset += Character.charCount(codePoint);
            }
        }
        return true;
    }
}
