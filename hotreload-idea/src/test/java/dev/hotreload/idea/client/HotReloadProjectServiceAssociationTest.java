package dev.hotreload.idea.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HotReloadProjectServiceAssociationTest {
    @Test void extractsExactlyOneLaunchIdFromTheProcessCommandLine() {
        String launch = UUID.randomUUID().toString();
        assertEquals(launch, HotReloadProjectService.launchIdFromCommandLine(
                "java \"-javaagent:C:/agent.jar=session=x,token=file:y,launch=" + launch + "\""));
        assertNull(HotReloadProjectService.launchIdFromCommandLine(
                "java -javaagent:a=launch=" + launch + ",launch=" + launch));
        assertNull(HotReloadProjectService.launchIdFromCommandLine("java -javaagent:a=launch=not-a-uuid"));
    }

    @Test void ignoresAnApplicationArgumentThatOnlyLooksLikeAHotReloadLaunchId() {
        String launch = UUID.randomUUID().toString();

        assertNull(HotReloadProjectService.launchIdFromCommandLine(
                "java -cp app.jar --launch=" + launch));
    }
}
