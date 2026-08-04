package dev.hotreload.agent.classes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight child loader used only for structural class replacements.
 * Parent-first for dependencies; defines only the reloaded class bytes.
 * Also serves the class bytes as a resource: Spring's
 * LocalVariableTableParameterNameDiscoverer reads {@code Xxx.class} via
 * getResourceAsStream to resolve handler parameter names on JDK8 builds
 * without {@code -parameters}; generation classes exist nowhere on disk.
 */
public final class GenerationClassLoader extends ClassLoader {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private final String tag;
    private final String targetBinaryName;
    private final String classResourceName;
    private final byte[] bytecode;
    private Class<?> defined;

    public GenerationClassLoader(ClassLoader parent, String targetBinaryName, byte[] bytecode) {
        super(parent == null ? ClassLoader.getSystemClassLoader() : parent);
        this.tag = "hr-gen-" + SEQ.incrementAndGet();
        this.targetBinaryName = targetBinaryName;
        this.classResourceName = targetBinaryName.replace('.', '/') + ".class";
        this.bytecode = bytecode;
    }

    public String getTag() { return tag; }

    public synchronized Class<?> defineTarget() {
        if (defined != null) return defined;
        defined = defineClass(targetBinaryName, bytecode, 0, bytecode.length);
        resolveClass(defined);
        return defined;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (targetBinaryName.equals(name) && defined != null) {
            if (resolve) resolveClass(defined);
            return defined;
        }
        return super.loadClass(name, resolve);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (classResourceName.equals(name)) {
            return new ByteArrayInputStream(bytecode);
        }
        return super.getResourceAsStream(name);
    }

    @Override
    protected URL findResource(String name) {
        if (!classResourceName.equals(name)) {
            return null;
        }
        try {
            return new URL("hotreload", null, -1, "/" + classResourceName,
                    new BytesUrlStreamHandler(bytecode));
        } catch (Throwable ignored) {
            // SecurityManager may forbid custom handlers; getResourceAsStream still serves bytes.
            return null;
        }
    }

    private static final class BytesUrlStreamHandler extends URLStreamHandler {
        private final byte[] bytes;

        BytesUrlStreamHandler(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new URLConnection(url) {
                @Override
                public void connect() { }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }
}
