package dev.hotreload.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionGuardTest {
    @Test void allowsOnlyOneOwnerUntilTheSessionReleasesIt() {
        AgentSessionGuard guard = new AgentSessionGuard();

        assertTrue(guard.tryAcquire());
        assertFalse(guard.tryAcquire());

        guard.release();
        assertTrue(guard.tryAcquire());
    }
}
