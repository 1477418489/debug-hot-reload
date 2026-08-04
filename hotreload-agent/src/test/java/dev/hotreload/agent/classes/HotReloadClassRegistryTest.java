package dev.hotreload.agent.classes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotReloadClassRegistryTest {
    @AfterEach
    void cleanup() {
        HotReloadClassRegistry.clear();
    }

    @Test
    void keepsLatestGenerationAndBoundedSize() {
        for (int i = 0; i < HotReloadClassRegistry.MAX_ENTRIES + 20; i++) {
            HotReloadClassRegistry.put("demo.Type" + i, String.class);
        }
        assertTrue(HotReloadClassRegistry.size() <= HotReloadClassRegistry.MAX_ENTRIES);
        HotReloadClassRegistry.put("demo.Keep", Integer.class);
        assertEquals(Integer.class, HotReloadClassRegistry.get("demo.Keep"));
        assertEquals(1, HotReloadClassRegistry.generation("demo.Keep"));
        HotReloadClassRegistry.put("demo.Keep", Long.class);
        assertEquals(Long.class, HotReloadClassRegistry.get("demo.Keep"));
        assertEquals(2, HotReloadClassRegistry.generation("demo.Keep"));
    }

    @Test
    void clearRemovesAll() {
        HotReloadClassRegistry.put("demo.A", String.class);
        HotReloadClassRegistry.clear();
        assertEquals(0, HotReloadClassRegistry.size());
        assertNull(HotReloadClassRegistry.get("demo.A"));
    }
}
