package dev.hotreload.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class BootstrapInstallerTest {
    @TempDir Path tempDirectory;

    @Test void extractsAppendsAndReleasesTheEmbeddedBootstrapJar() throws Exception {
        AtomicReference<JarFile> appended = new AtomicReference<JarFile>();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Instrumentation.class}, (proxy, method, arguments) -> {
                    if ("appendToBootstrapClassLoaderSearch".equals(method.getName())) {
                        appended.set((JarFile) arguments[0]);
                    }
                    return primitiveDefault(method.getReturnType());
                });

        BootstrapInstaller.Installation installation = BootstrapInstaller.install(instrumentation, tempDirectory);
        Path extracted = installation.getExtractedPath();
        assertTrue(Files.exists(extracted));
        assertNotNull(appended.get());
        assertNotNull(appended.get().getJarEntry("dev/hotreload/bootstrap/HotReloadBridge.class"));

        installation.close();
        installation.close();
        assertFalse(Files.exists(extracted));
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return (char) 0;
        return null;
    }
}
