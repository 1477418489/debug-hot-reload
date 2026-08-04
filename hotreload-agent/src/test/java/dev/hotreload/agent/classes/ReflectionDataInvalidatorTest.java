package dev.hotreload.agent.classes;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionDataInvalidatorTest {
    @Test
    void invalidateDoesNotThrowForLoadedClass() {
        assertDoesNotThrow(() -> ReflectionDataInvalidator.invalidate(ReflectionDataInvalidatorTest.class));
        int count = ReflectionDataInvalidator.invalidateAll(Collections.<Class<?>>singletonList(ReflectionDataInvalidatorTest.class));
        assertTrue(count >= 0);
    }
}
