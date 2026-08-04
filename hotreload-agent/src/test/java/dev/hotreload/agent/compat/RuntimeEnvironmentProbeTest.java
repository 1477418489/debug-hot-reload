package dev.hotreload.agent.compat;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeEnvironmentProbeTest {
    @Test
    void probeWithoutFrameworksStillReportsJdkAndCapabilities() {
        RuntimeEnvironment env = RuntimeEnvironmentProbe.probe(true);
        assertNotNull(env.getJdkVersion());
        assertFalse(env.getJdkVersion().isEmpty());
        assertTrue(env.isClassRedefineSupported());
        assertTrue(env.getCapabilities().contains("mapperXml"));
        assertTrue(env.getCapabilities().contains("classRedefine"));
        assertTrue(env.summary().contains("jdk="));
        assertEquals("true", env.asLogFields().get("classRedefine"));
    }

    @Test
    void environmentSummaryIsBounded() {
        RuntimeEnvironment env = new RuntimeEnvironment("1.8.0_172", "Oracle", null, null, null, null,
                false, true, false, false, Collections.singletonList("mapperXml"));
        assertTrue(env.summary().length() < 300);
        assertEquals("none", env.asLogFields().get("spring"));
    }
}
