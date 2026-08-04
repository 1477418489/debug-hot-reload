package dev.hotreload.idea.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DebugProgramPatcherTest {
    @Test void parsesLegacyAndModernJdkVersionStrings() {
        assertEquals(8, DebugProgramPatcher.jdkFeature("java version \"1.8.0_402\"").intValue());
        assertEquals(21, DebugProgramPatcher.jdkFeature("21.0.11").intValue());
        assertNull(DebugProgramPatcher.jdkFeature("not-a-jdk"));
        assertNull(DebugProgramPatcher.jdkFeature((String) null));
    }
}
