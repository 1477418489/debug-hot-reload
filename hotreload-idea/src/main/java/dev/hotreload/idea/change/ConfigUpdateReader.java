package dev.hotreload.idea.change;

import dev.hotreload.protocol.ProtocolLimits;
import dev.hotreload.protocol.resource.ResourceId;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Reads one supported Spring configuration resource from a verified source root. */
public final class ConfigUpdateReader {
    private ConfigUpdateReader() {
    }

    public static Result read(Path sourceRoot, Path file) throws IOException {
        if (sourceRoot == null || file == null) {
            throw new NullPointerException("sourceRoot and file are required");
        }
        Path root = PathSafety.realDirectory(sourceRoot);
        Path target = PathSafety.containedFile(root, file);
        String relative = root.relativize(target).toString().replace(File.separatorChar, '/');
        String resourceId = ResourceId.of(relative).value();
        if (!isReloadableConfigPath(resourceId)) {
            throw new IllegalArgumentException("file is not a supported configuration resource");
        }
        byte[] content = StableFileReader.read(target, ProtocolLimits.MAX_ITEM_BYTES, true);
        return new Result(resourceId, content);
    }

    static boolean isReloadableConfigPath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return false;
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("static/") || normalized.startsWith("public/")
                || normalized.startsWith("resources/")
                || normalized.startsWith("meta-inf/resources/")
                || normalized.startsWith("templates/")) {
            return false;
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        if (name.equals("application.properties") || name.equals("bootstrap.properties")) {
            return true;
        }
        if (name.equals("application.yml") || name.equals("application.yaml")
                || name.equals("bootstrap.yml") || name.equals("bootstrap.yaml")) {
            return true;
        }
        boolean supportedExtension = name.endsWith(".properties")
                || name.endsWith(".yml") || name.endsWith(".yaml");
        if (!supportedExtension) return false;
        int extension = name.lastIndexOf('.');
        String stem = extension < 0 ? name : name.substring(0, extension);
        return (stem.startsWith("application-") && stem.length() > "application-".length())
                || (stem.startsWith("bootstrap-") && stem.length() > "bootstrap-".length());
    }

    public static final class Result {
        private final String resourceId;
        private final byte[] content;

        private Result(String resourceId, byte[] content) {
            this.resourceId = resourceId;
            this.content = content.clone();
        }

        public String getResourceId() { return resourceId; }
        public byte[] getContent() { return content.clone(); }
        public int getContentLength() { return content.length; }
    }
}
