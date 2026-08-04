package dev.hotreload.idea.client;

import dev.hotreload.protocol.session.SessionDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunningSessionRegistryTest {
    @Test void routesOnlyWhenExactlyOneSessionIsActiveAndClosesAllClients() {
        RunningSessionRegistry registry = new RunningSessionRegistry(2);
        HotReloadClient first = client("first", 1234);
        HotReloadClient second = client("second", 1235);

        assertTrue(registry.add("first", first));
        assertEquals("first", registry.only().getLaunchId());
        assertTrue(registry.add("second", second));
        assertNull(registry.only());
        assertEquals("first", registry.get("first").getLaunchId());
        assertEquals("second", registry.get("second").getLaunchId());
        assertNull(registry.get("missing"));
        assertEquals(2, registry.snapshot().size());
        assertSame(second, registry.remove("second"));
        assertEquals("first", registry.only().getLaunchId());

        registry.close();
        assertTrue(first.isClosed());
        assertEquals(0, registry.size());
    }

    @Test void rejectsSessionsBeyondCapacityWithoutTakingOwnership() {
        RunningSessionRegistry registry = new RunningSessionRegistry(1);
        HotReloadClient accepted = client("accepted", 1234);
        HotReloadClient rejected = client("rejected", 1235);
        try {
            assertTrue(registry.add("accepted", accepted));
            assertFalse(registry.add("rejected", rejected));
            assertFalse(rejected.isClosed());
        } finally {
            registry.close();
            rejected.close();
        }
    }

    private static HotReloadClient client(String launchId, int port) {
        return new HotReloadClient(new SessionDescriptor(launchId, 1, port), "token", launchId);
    }
}
