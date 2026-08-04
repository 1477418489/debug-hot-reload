package dev.hotreload.bootstrap;

import java.net.URI;
import java.nio.charset.StandardCharsets;

final class RuntimeResourceId {
    private static final int MAX_BYTES = 16 * 1024;
    private static final String[] EXPLICIT_OUTPUT_MARKERS = {
            "/target/test-classes/", "/target/classes/",
            "/build/resources/test/", "/build/resources/main/",
            "/build/classes/java/test/", "/build/classes/java/main/",
            "/out/test/", "/out/production/"
    };
    private static final String[] GENERIC_OUTPUT_MARKERS = {
            "/test-classes/", "/classes/"
    };
    private static final MarkerMatch AMBIGUOUS_MARKER = new MarkerMatch(-1, null);

    private RuntimeResourceId() {
    }

    static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String value = unwrap(raw.trim());
        if (value == null || value.isEmpty()) return null;
        if (looksLikeFileUri(value)) value = fileUriPath(value);
        if (value == null) return null;
        value = value.replace('\\', '/');
        if (value.indexOf("!/") >= 0 || value.indexOf("!\\") >= 0) return null;
        if (isAbsolute(value)) return fromAbsolutePath(value);
        return validateRelative(value);
    }

    private static String unwrap(String value) {
        if ((value.startsWith("file [") || value.startsWith("URL [")) && value.endsWith("]")) {
            return value.substring(value.indexOf('[') + 1, value.length() - 1).trim();
        }
        return value;
    }

    private static String fileUriPath(String value) {
        try {
            URI uri = new URI(value);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            return uri.getPath();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean looksLikeFileUri(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || value.matches("^[A-Za-z]:[/\\\\].*")) return false;
        String scheme = value.substring(0, colon);
        return "file".equalsIgnoreCase(scheme) || scheme.matches("[A-Za-z][A-Za-z0-9+.-]*");
    }

    private static boolean isAbsolute(String value) {
        return value.startsWith("/") || value.matches("^[A-Za-z]:/.*")
                || value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    }

    private static String fromAbsolutePath(String value) {
        MarkerMatch match = findUniqueMarker(value, EXPLICIT_OUTPUT_MARKERS);
        if (match == AMBIGUOUS_MARKER) return null;
        if (match == null) {
            match = findUniqueMarker(value, GENERIC_OUTPUT_MARKERS);
            if (match == null || match == AMBIGUOUS_MARKER) return null;
        }
        String suffix = value.substring(match.end);
        if ("/out/production/".equals(match.marker) || "/out/test/".equals(match.marker)) {
            suffix = afterModuleDirectory(suffix);
        }
        return validateRelative(suffix);
    }

    private static MarkerMatch findUniqueMarker(String value, String[] markers) {
        MarkerMatch match = null;
        for (String marker : markers) {
            int lastStart = value.length() - marker.length();
            for (int index = 0; index <= lastStart; index++) {
                if (!value.regionMatches(true, index, marker, 0, marker.length())) continue;
                if (match != null) return AMBIGUOUS_MARKER;
                match = new MarkerMatch(index, marker);
            }
        }
        return match;
    }

    private static String afterModuleDirectory(String value) {
        int separator = value.indexOf('/');
        return separator < 0 ? "" : value.substring(separator + 1);
    }

    private static String validateRelative(String value) {
        while (value.startsWith("./")) value = value.substring(2);
        if (value.isEmpty() || value.startsWith("/") || value.matches("^[A-Za-z]:.*")
                || value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) return null;
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return null;
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return null;
        }
        return value;
    }

    private static final class MarkerMatch {
        private final int end;
        private final String marker;

        private MarkerMatch(int start, String marker) {
            this.end = start < 0 ? -1 : start + marker.length();
            this.marker = marker;
        }
    }
}
