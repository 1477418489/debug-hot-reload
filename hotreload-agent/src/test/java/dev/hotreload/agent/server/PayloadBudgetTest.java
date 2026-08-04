package dev.hotreload.agent.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadBudgetTest {
    @Test void aFailedReleaseDoesNotCorruptTheRetainedByteCount() {
        PayloadBudget budget = new PayloadBudget(10);
        assertEquals(true, budget.tryReserve(4));

        assertThrows(IllegalStateException.class, () -> budget.release(5));

        assertEquals(4, budget.getRetainedBytes());
    }

    @Test void negativeReleaseIsRejected() {
        PayloadBudget budget = new PayloadBudget(10);

        assertThrows(IllegalArgumentException.class, () -> budget.release(-1));
        assertEquals(0, budget.getRetainedBytes());
    }
}
