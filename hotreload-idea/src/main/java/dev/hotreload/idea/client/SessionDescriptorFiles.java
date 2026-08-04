package dev.hotreload.idea.client;

import dev.hotreload.protocol.session.SessionDescriptor;
import dev.hotreload.idea.change.PathSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

final class SessionDescriptorFiles {
    private SessionDescriptorFiles() {
    }

    static boolean deleteIfOwned(Path descriptorPath, String launchId) throws IOException {
        return deleteIfOwned(descriptorPath, launchId, null);
    }

    static boolean deleteIfOwned(Path descriptorPath, String launchId, String token) throws IOException {
        if (!isSafePath(descriptorPath) || Files.isSymbolicLink(descriptorPath)
                || !Files.isRegularFile(descriptorPath, LinkOption.NOFOLLOW_LINKS)) return false;
        BasicFileAttributes before = Files.readAttributes(descriptorPath, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        SessionDescriptor descriptor = SessionDescriptor.read(descriptorPath);
        if (!launchId.equals(descriptor.getLaunchId())) return false;
        if (token != null && !descriptor.verifies(token)) return false;
        BasicFileAttributes after = Files.readAttributes(descriptorPath, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) return false;
        return Files.deleteIfExists(descriptorPath);
    }

    static boolean isSafePath(Path path) {
        try {
            return PathSafety.realDirectory(path.getParent()).equals(path.getParent().toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }
}
