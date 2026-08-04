package dev.hotreload.idea.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnhancedRuntimeSupportTest {
    @TempDir Path temporaryDirectory;

    @Test void detectsOnlyDcevmDirectoriesContainingAJvmLibrary() throws Exception {
        Path emptyDcevm = temporaryDirectory.resolve("empty/bin/dcevm");
        Files.createDirectories(emptyDcevm);
        EnhancedRuntimeSupport.Result empty = EnhancedRuntimeSupport.inspect(
                temporaryDirectory.resolve("empty"), 8);
        assertFalse(empty.isAvailable());

        Path usableHome = temporaryDirectory.resolve("usable");
        Path dcevm = usableHome.resolve("jre/bin/dcevm");
        Files.createDirectories(dcevm);
        Files.write(dcevm.resolve("jvm.dll"), new byte[]{1});
        EnhancedRuntimeSupport.Result usable = EnhancedRuntimeSupport.inspect(usableHome, 8);

        assertEquals(EnhancedRuntimeSupport.Mode.DCEVM, usable.getMode());
        assertEquals(Arrays.asList("-XXaltjvm=dcevm", "-XX:TieredStopAtLevel=1"),
                usable.getVmArguments());
    }

    @Test void requiresJdk17ForJetBrainsEnhancedRedefinition() throws Exception {
        Path home = temporaryDirectory.resolve("jbr");
        Files.createDirectories(home);
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"JetBrains s.r.o.\"\n");

        EnhancedRuntimeSupport.Result old = EnhancedRuntimeSupport.inspect(home, 11);
        EnhancedRuntimeSupport.Result supported = EnhancedRuntimeSupport.inspect(home, 21);

        assertEquals(EnhancedRuntimeSupport.Reason.JBR_REQUIRES_JDK_17, old.getReason());
        assertEquals(EnhancedRuntimeSupport.Mode.JBR, supported.getMode());
        assertEquals(Collections.singletonList("-XX:+AllowEnhancedClassRedefinition"),
                supported.getVmArguments());
    }

    @Test void parsesLegacyAndModernJdkVersions() {
        assertEquals(8, EnhancedRuntimeSupport.jdkFeature(
                "java version \"1.8.0_402\"").intValue());
        assertEquals(21, EnhancedRuntimeSupport.jdkFeature("21.0.11").intValue());
        assertNull(EnhancedRuntimeSupport.jdkFeature("not-a-jdk"));
        assertNull(EnhancedRuntimeSupport.jdkFeature(null));
    }

    @Test void reportsMissingHomeWithoutThrowing() {
        EnhancedRuntimeSupport.Result result = EnhancedRuntimeSupport.inspect(
                temporaryDirectory.resolve("missing"), 21);
        assertFalse(result.isAvailable());
        assertEquals(EnhancedRuntimeSupport.Reason.HOME_MISSING, result.getReason());
        assertTrue(result.getVmArguments().isEmpty());
    }
}
