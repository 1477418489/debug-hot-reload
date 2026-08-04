package dev.hotreload.idea.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentLaunchSpecTest {
    @TempDir Path tempDirectory;

    @Test void createsOneLaunchDirectoryAndASecretFreeVmArgumentFormat() throws Exception {
        Path agent = tempDirectory.resolve("hotreload-agent.jar").toAbsolutePath();
        Files.write(agent, new byte[]{1});
        Path launches = tempDirectory.resolve("launches").toAbsolutePath();

        AgentLaunchSpec spec = AgentLaunchSpec.create(launches, agent);

        assertTrue(Files.isDirectory(spec.getLaunchDirectory()));
        assertEquals(spec.getLaunchDirectory(), spec.getSessionPath().getParent());
        assertEquals(spec.getLaunchDirectory(), spec.getLogPath().getParent());
        assertTrue(spec.getVmArgument().startsWith("-javaagent:" + agent + "="));
        assertTrue(spec.getAgentOptions().contains("session="));
        assertTrue(spec.getAgentOptions().contains("log="));
        assertTrue(spec.getAgentOptions().contains("token="));
        assertTrue(spec.getAgentOptions().contains("token=file:"));
        assertTrue(Files.exists(spec.getCredentialPath()));
        assertFalse(spec.getVmArgument().contains(spec.getToken()));
        assertTrue(spec.getAgentOptions().contains("launch=" + spec.getLaunchId()));
        assertFalse(spec.getAgentOptions().contains("mode="));
        assertFalse(spec.getAgentOptions().contains("verbose="));
        assertFalse(spec.toString().contains(spec.getToken()));
    }

    @Test void appendsVerboseFlagWhenEnabled() throws Exception {
        Path agent = tempDirectory.resolve("hotreload-agent-verbose.jar").toAbsolutePath();
        Files.write(agent, new byte[]{1});
        Path launches = tempDirectory.resolve("launches-verbose").toAbsolutePath();
        AgentLaunchSpec spec = AgentLaunchSpec.create(launches, agent, true);
        assertTrue(spec.getAgentOptions().contains("verbose=true"), spec.getAgentOptions());
    }
}
