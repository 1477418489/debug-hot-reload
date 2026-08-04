package dev.hotreload.agent.classes;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NewClassDefiner {
    private NewClassDefiner() { }

    public static Class<?> define(String binaryName, byte[] bytecode, Instrumentation instrumentation)
            throws Exception {
        if (binaryName == null || binaryName.isEmpty()) throw new IllegalArgumentException("binaryName");
        if (bytecode == null || bytecode.length == 0) throw new IllegalArgumentException("bytecode");
        Exception last = null;

        // On JDK 9+, ClassLoader#defineClass is strongly encapsulated. A private lookup on an
        // already-loaded class in the target package defines the new type in that exact loader.
        List<Class<?>> neighbors = samePackageNeighbors(instrumentation, binaryName);
        for (Class<?> neighbor : neighbors) {
            try {
                return defineWithLookup(neighbor, binaryName, bytecode);
            } catch (Exception failure) {
                last = failure;
            }
        }

        // JDK 8 fallback. On newer JDKs this can still work when java.lang is explicitly opened.
        List<ClassLoader> loaders = definitionLoaders(neighbors);
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                return defineWith(loader, binaryName, bytecode);
            } catch (Exception failure) {
                last = failure;
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("No classloader available to define " + binaryName);
    }

    private static Class<?> defineWithLookup(Class<?> neighbor, String binaryName, byte[] bytecode)
            throws Exception {
        Method privateLookupIn = MethodHandles.class.getMethod(
                "privateLookupIn", Class.class, MethodHandles.Lookup.class);
        Object privateLookup = privateLookupIn.invoke(null, neighbor, MethodHandles.lookup());
        Method defineClass = MethodHandles.Lookup.class.getMethod("defineClass", byte[].class);
        Class<?> defined = (Class<?>) defineClass.invoke(privateLookup, new Object[]{bytecode});
        verifyDefinition(defined, binaryName, neighbor.getClassLoader());
        return defined;
    }

    private static Class<?> defineWith(ClassLoader loader, String binaryName, byte[] bytecode) throws Exception {
        Method defineClass = ClassLoader.class.getDeclaredMethod(
                "defineClass", String.class, byte[].class, int.class, int.class);
        defineClass.setAccessible(true);
        Class<?> defined = (Class<?>) defineClass.invoke(loader, binaryName, bytecode, Integer.valueOf(0),
                Integer.valueOf(bytecode.length));
        verifyDefinition(defined, binaryName, loader);
        return defined;
    }

    private static void verifyDefinition(Class<?> defined, String binaryName, ClassLoader expectedLoader) {
        if (defined == null || !binaryName.equals(defined.getName())) {
            throw new IllegalStateException("Defined class name mismatch for " + binaryName);
        }
        if (defined.getClassLoader() != expectedLoader) {
            throw new IllegalStateException("Class was not defined in the target application loader: "
                    + binaryName);
        }
    }

    private static List<Class<?>> samePackageNeighbors(Instrumentation instrumentation, String binaryName) {
        if (instrumentation == null) return new ArrayList<Class<?>>(0);
        String packageName = packageName(binaryName);
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        Set<Class<?>> preferred = new LinkedHashSet<Class<?>>();
        Set<Class<?>> remaining = new LinkedHashSet<Class<?>>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded == null || loaded.isArray() || loaded.isPrimitive()
                    || loaded.getClassLoader() == null || binaryName.equals(loaded.getName())
                    || !packageName.equals(packageName(loaded.getName()))) {
                continue;
            }
            if (loaded.getClassLoader() == contextLoader) preferred.add(loaded);
            else remaining.add(loaded);
        }
        if (!preferred.isEmpty()) return new ArrayList<Class<?>>(preferred);
        Set<ClassLoader> loaders = new LinkedHashSet<ClassLoader>();
        for (Class<?> neighbor : remaining) loaders.add(neighbor.getClassLoader());
        if (loaders.size() > 1) {
            throw new IllegalStateException("Ambiguous application classloader for " + binaryName);
        }
        return new ArrayList<Class<?>>(remaining);
    }

    private static List<ClassLoader> definitionLoaders(List<Class<?>> neighbors) {
        Set<ClassLoader> loaders = new LinkedHashSet<ClassLoader>();
        for (Class<?> neighbor : neighbors) loaders.add(neighbor.getClassLoader());
        if (loaders.isEmpty()) {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context != null) loaders.add(context);
            else {
                ClassLoader system = ClassLoader.getSystemClassLoader();
                if (system != null) loaders.add(system);
            }
        }
        return new ArrayList<ClassLoader>(loaders);
    }

    private static String packageName(String binaryName) {
        int separator = binaryName == null ? -1 : binaryName.lastIndexOf('.');
        return separator < 0 ? "" : binaryName.substring(0, separator);
    }
}
