package dev.hotreload.agent.classes;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers that avoid repeated full {@link Instrumentation#getAllLoadedClasses()} scans.
 */
public final class LoadedClassLookup {
    private LoadedClassLookup() { }

    public static Map<String, List<Class<?>>> indexRequested(Instrumentation instrumentation,
                                                             Collection<String> binaryNames) {
        Map<String, List<Class<?>>> result = new HashMap<String, List<Class<?>>>();
        if (instrumentation == null || binaryNames == null || binaryNames.isEmpty()) {
            return result;
        }
        Set<String> requested = binaryNames instanceof Set
                ? (Set<String>) binaryNames
                : new LinkedHashSet<String>(binaryNames);
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded == null) continue;
            String name = loaded.getName();
            if (!requested.contains(name)) continue;
            List<Class<?>> matches = result.get(name);
            if (matches == null) {
                matches = new ArrayList<Class<?>>(2);
                result.put(name, matches);
            }
            matches.add(loaded);
        }
        return result;
    }

    public static Class<?> find(Instrumentation instrumentation, String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return null;
        ClassLoader[] loaders = new ClassLoader[] {
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                LoadedClassLookup.class.getClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                return Class.forName(binaryName, false, loader);
            } catch (Throwable ignored) {
            }
        }
        if (instrumentation == null) return null;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded != null && binaryName.equals(loaded.getName())) {
                return loaded;
            }
        }
        return null;
    }

    public static List<ClassLoader> candidateLoaders(Instrumentation instrumentation,
                                                     String binaryName, int maxLoaders) {
        Set<ClassLoader> ordered = new LinkedHashSet<ClassLoader>();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) ordered.add(context);
        if (instrumentation != null) {
            String packagePrefix = packagePrefix(binaryName);
            // Single scan: prefer same package, then other app loaders.
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded == null) continue;
                ClassLoader loader = loaded.getClassLoader();
                if (loader == null) continue;
                String name = loaded.getName();
                if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.")
                        || name.startsWith("jdk.") || name.startsWith("com.intellij.")
                        || name.startsWith("org.jetbrains.") || name.startsWith("dev.hotreload.")) {
                    continue;
                }
                if (packagePrefix != null && name.startsWith(packagePrefix)) {
                    ordered.add(loader);
                } else if (ordered.size() < maxLoaders) {
                    ordered.add(loader);
                }
                if (ordered.size() >= maxLoaders) break;
            }
        }
        ordered.add(ClassLoader.getSystemClassLoader());
        return new ArrayList<ClassLoader>(ordered);
    }

    private static String packagePrefix(String binaryName) {
        if (binaryName == null) return null;
        int index = binaryName.lastIndexOf('.');
        if (index <= 0) return null;
        int second = binaryName.lastIndexOf('.', index - 1);
        if (second <= 0) return binaryName.substring(0, index + 1);
        return binaryName.substring(0, second + 1);
    }
}
