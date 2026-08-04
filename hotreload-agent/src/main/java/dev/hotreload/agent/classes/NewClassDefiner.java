package dev.hotreload.agent.classes;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.List;

public final class NewClassDefiner {
    private NewClassDefiner() { }

    public static Class<?> define(String binaryName, byte[] bytecode, Instrumentation instrumentation)
            throws Exception {
        if (binaryName == null || binaryName.isEmpty()) throw new IllegalArgumentException("binaryName");
        if (bytecode == null || bytecode.length == 0) throw new IllegalArgumentException("bytecode");
        List<ClassLoader> loaders = LoadedClassLookup.candidateLoaders(instrumentation, binaryName, 64);
        Exception last = null;
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

    private static Class<?> defineWith(ClassLoader loader, String binaryName, byte[] bytecode) throws Exception {
        Method defineClass = ClassLoader.class.getDeclaredMethod(
                "defineClass", String.class, byte[].class, int.class, int.class);
        defineClass.setAccessible(true);
        return (Class<?>) defineClass.invoke(loader, binaryName, bytecode, Integer.valueOf(0),
                Integer.valueOf(bytecode.length));
    }
}
