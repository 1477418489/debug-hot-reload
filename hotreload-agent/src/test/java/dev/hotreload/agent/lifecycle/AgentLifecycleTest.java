package dev.hotreload.agent.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLifecycleTest {
    @Test void closesResourcesInReverseOrderOnlyOnce() {
        AgentLifecycle lifecycle = new AgentLifecycle();
        List<String> closed = new ArrayList<String>();
        lifecycle.register(() -> closed.add("first"));
        lifecycle.register(() -> closed.add("second"));

        lifecycle.close();
        lifecycle.close();

        assertEquals(Arrays.asList("second", "first"), closed);
        assertTrue(lifecycle.isClosed());
    }

    @Test void closesResourcesRegisteredAfterShutdownImmediately() {
        AgentLifecycle lifecycle = new AgentLifecycle();
        lifecycle.close();
        List<String> closed = new ArrayList<String>();

        lifecycle.register(() -> closed.add("late"));

        assertEquals(Arrays.asList("late"), closed);
    }
}
