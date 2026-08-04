package dev.hotreload.idea.change;

import dev.hotreload.protocol.resource.ResourceId;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipException;

/**
 * Decides whether a resource can be safely reloaded for the current Debug classpath.
 *
 * Multi-module apps often have several target/classes roots. The ordered Debug classpath decides
 * which duplicate is actually visible. Mapper XML may fall back to source content while its output
 * copy is temporarily missing, but only when no other classpath entry currently supplies that ID.
 * Static resources use
 * {@link #evaluateOrderedResource(Path, String, Collection)} before installation, while
 * configuration resources use {@link #evaluateLoadedResource(Path, String, Collection)}.
 */
public final class DebugClasspathMatcher {
    public enum DecisionCode {
        OK,
        OK_SOURCE_FALLBACK,
        BAD_INPUT,
        OUTPUT_NOT_ON_CLASSPATH,
        RESOURCE_SHADOWED,
        RESOURCE_NOT_IN_OUTPUT,
        PATH_UNSAFE
    }

    public static final class Decision {
        private final boolean accepted;
        private final DecisionCode code;
        private final int matchCount;
        private final List<String> matchedRoots;
        private final String preferredRoot;

        private Decision(boolean accepted, DecisionCode code, int matchCount,
                         List<String> matchedRoots, String preferredRoot) {
            this.accepted = accepted;
            this.code = code;
            this.matchCount = matchCount;
            this.matchedRoots = matchedRoots == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(matchedRoots));
            this.preferredRoot = preferredRoot == null ? "" : preferredRoot;
        }

        public boolean isAccepted() { return accepted; }
        public DecisionCode getCode() { return code; }
        public int getMatchCount() { return matchCount; }
        public List<String> getMatchedRoots() { return matchedRoots; }
        public String getPreferredRoot() { return preferredRoot; }

        public String reason() {
            switch (code) {
                case OK: return "ok";
                case OK_SOURCE_FALLBACK: return "ok_source_content_fallback";
                case BAD_INPUT: return "bad_input";
                case OUTPUT_NOT_ON_CLASSPATH: return "output_not_on_debug_classpath";
                case RESOURCE_SHADOWED: return "resource_shadowed_on_debug_classpath";
                case RESOURCE_NOT_IN_OUTPUT: return "resource_missing_in_module_output";
                case PATH_UNSAFE: return "path_unsafe";
                default: return code.name().toLowerCase(Locale.ROOT);
            }
        }

        public String summary() {
            return "code=" + reason()
                    + ",matchCount=" + matchCount
                    + ",preferredRoot=" + (preferredRoot.isEmpty() ? "none" : preferredRoot)
                    + ",matchedRoots=" + (matchedRoots.isEmpty() ? "none" : String.join(";", matchedRoots));
        }
    }

    private DebugClasspathMatcher() {
    }

    public static boolean isReloadableMapperResource(Path outputRoot, String resourceId,
                                                     Collection<Path> classpathEntries) {
        return evaluateMapperResource(outputRoot, resourceId, classpathEntries).isAccepted();
    }

    /** @deprecated Use {@link #evaluateMapperResource(Path, String, Collection)}. */
    @Deprecated
    public static Decision evaluate(Path outputRoot, String resourceId,
                                    Collection<Path> classpathEntries) {
        return evaluateMapperResource(outputRoot, resourceId, classpathEntries);
    }

    public static Decision evaluateMapperResource(Path outputRoot, String resourceId,
                                                  Collection<Path> classpathEntries) {
        return evaluateOrderedResource(outputRoot, resourceId, classpathEntries, false, true);
    }

    /**
     * Accepts a static-resource update only when the event output is the first classpath entry
     * capable of serving that resource after installation.
     */
    public static Decision evaluateOrderedResource(Path outputRoot, String resourceId,
                                                   Collection<Path> classpathEntries) {
        return evaluateOrderedResource(outputRoot, resourceId, classpathEntries, false, false);
    }

    /**
     * Accepts a configuration update only when the event output currently supplies the resource.
     * Unlike static resources, configuration payloads are not installed into the output first.
     */
    public static Decision evaluateLoadedResource(Path outputRoot, String resourceId,
                                                  Collection<Path> classpathEntries) {
        return evaluateOrderedResource(outputRoot, resourceId, classpathEntries, true, false);
    }

    private static Decision evaluateOrderedResource(Path outputRoot, String resourceId,
                                                    Collection<Path> classpathEntries,
                                                    boolean requireResourceInOutput,
                                                    boolean allowSourceFallback) {
        if (outputRoot == null || resourceId == null || classpathEntries == null) {
            return decision(false, DecisionCode.BAD_INPUT, 0, null, null);
        }
        final String normalizedResource;
        final Path output;
        try {
            normalizedResource = ResourceId.of(resourceId).value();
            output = PathSafety.realDirectory(outputRoot);
        } catch (IllegalArgumentException e) {
            return decision(false, DecisionCode.BAD_INPUT, 0, null, null);
        } catch (Exception e) {
            return decision(false, DecisionCode.PATH_UNSAFE, 0, null, null);
        }

        final Path relative;
        try {
            relative = Paths.get(normalizedResource.replace('/', File.separatorChar));
        } catch (RuntimeException invalidPath) {
            return decision(false, DecisionCode.BAD_INPUT, 0, null, output.toString());
        }

        // Normalize the complete ordered classpath before inspecting resources.  Returning a
        // shadowing decision before proving that the event output is actually present would
        // otherwise allow writes into an output directory that is no longer part of the JVM.
        List<Path> orderedEntries = new ArrayList<Path>();
        Set<Path> visited = new LinkedHashSet<Path>();
        for (Path rawEntry : classpathEntries) {
            if (rawEntry == null) continue;
            final Path entry;
            try {
                if (Files.isDirectory(rawEntry, LinkOption.NOFOLLOW_LINKS)) {
                    entry = PathSafety.realDirectory(rawEntry);
                } else if (Files.isRegularFile(rawEntry, LinkOption.NOFOLLOW_LINKS)) {
                    entry = PathSafety.realFile(rawEntry);
                } else {
                    continue;
                }
            } catch (Exception ignored) {
                continue;
            }
            if (!visited.add(entry)) continue;
            orderedEntries.add(entry);
        }

        int outputIndex = orderedEntries.indexOf(output);
        if (outputIndex < 0) {
            return decision(false, DecisionCode.OUTPUT_NOT_ON_CLASSPATH, 0, null,
                    output.toString());
        }

        for (int index = 0; index < outputIndex; index++) {
            Path entry = orderedEntries.get(index);
            try {
                if (containsResource(entry, relative, normalizedResource)) {
                    return shadowed(output, entry);
                }
            } catch (IllegalArgumentException e) {
                return decision(false, DecisionCode.PATH_UNSAFE, 0,
                        Collections.singletonList(entry.toString()), output.toString());
            } catch (Exception e) {
                return decision(false, DecisionCode.PATH_UNSAFE, 0,
                        Collections.singletonList(entry.toString()), output.toString());
            }
        }

        Path candidate = output.resolve(relative).normalize();
        boolean presentInOutput = Files.exists(candidate, LinkOption.NOFOLLOW_LINKS);
        if (presentInOutput) {
            try {
                PathSafety.resolveContained(output, relative, true);
            } catch (Exception unsafe) {
                return decision(false, DecisionCode.PATH_UNSAFE, 0,
                        Collections.singletonList(output.toString()), output.toString());
            }
        } else if (requireResourceInOutput) {
                return decision(false, DecisionCode.RESOURCE_NOT_IN_OUTPUT, 0,
                        null, output.toString());
        }

        if (allowSourceFallback) {
            List<String> matched = new ArrayList<String>();
            if (presentInOutput) matched.add(output.toString());
            for (int index = outputIndex + 1; index < orderedEntries.size(); index++) {
                Path entry = orderedEntries.get(index);
                try {
                    if (!containsResource(entry, relative, normalizedResource)) continue;
                    if (!presentInOutput) return shadowed(output, entry);
                    matched.add(entry.toString());
                } catch (Exception unsafe) {
                    if (!presentInOutput) {
                        return decision(false, DecisionCode.PATH_UNSAFE, 0,
                                Collections.singletonList(entry.toString()), output.toString());
                    }
                }
            }
            if (!presentInOutput) {
                return decision(true, DecisionCode.OK_SOURCE_FALLBACK, 0,
                        Collections.<String>emptyList(), output.toString());
            }
            return decision(true, DecisionCode.OK, matched.size(), matched, output.toString());
        }
        return decision(true, DecisionCode.OK, 1,
                Collections.singletonList(output.toString()), output.toString());
    }

    private static Decision shadowed(Path output, Path entry) {
        return decision(false, DecisionCode.RESOURCE_SHADOWED, 1,
                Collections.singletonList(entry.toString()), output.toString());
    }

    private static boolean jarContains(Path archive, String resourceId) throws java.io.IOException {
        try {
            try (JarFile jar = new JarFile(archive.toFile())) {
                return jar.getJarEntry(resourceId) != null;
            }
        } catch (ZipException notAnArchive) {
            // A Java classpath may contain ordinary files (for example a generated argument
            // file).  They are not resource archives and must not block a later output root.
            return false;
        }
    }

    private static boolean containsResource(Path entry, Path relative, String resourceId)
            throws java.io.IOException {
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            Path candidate = entry.resolve(relative).normalize();
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return false;
            if (Files.isSymbolicLink(candidate)) {
                throw new IllegalArgumentException("classpath resource is symbolic");
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return false;
            PathSafety.resolveContained(entry, relative, true);
            return true;
        }
        return jarContains(entry, resourceId);
    }

    private static Decision decision(boolean accepted, DecisionCode code, int matchCount,
                                     List<String> matchedRoots, String preferredRoot) {
        return new Decision(accepted, code, matchCount, matchedRoots, preferredRoot);
    }

    public static boolean containsOutputRoot(Path outputRoot, Collection<Path> classpathEntries) {
        if (outputRoot == null || classpathEntries == null) return false;
        final Path output;
        try {
            output = PathSafety.realDirectory(outputRoot);
        } catch (Exception e) {
            return false;
        }
        for (Path entry : classpathEntries) {
            if (entry == null) continue;
            try {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                        && output.equals(PathSafety.realDirectory(entry))) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
