package dev.hotreload.idea.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DebugLaunchPolicyTest {
    @Test void acceptsOnlyLocalJavaAndSpringBootDebugOnJdk8OrNewer() {
        assertTrue(DebugLaunchPolicy.isSupported("Debug", "Application", 8));
        assertTrue(DebugLaunchPolicy.isSupported("Debug", "SpringBootApplicationConfigurationType", 21));

        assertFalse(DebugLaunchPolicy.isSupported("Run", "Application", 21));
        assertFalse(DebugLaunchPolicy.isSupported("Debug", "JUnit", 21));
        assertFalse(DebugLaunchPolicy.isSupported("Debug", "Remote", 21));
        assertFalse(DebugLaunchPolicy.isSupported("Debug", "Application", 7));
        assertFalse(DebugLaunchPolicy.isSupported("Debug", "Application", null));
    }
}
