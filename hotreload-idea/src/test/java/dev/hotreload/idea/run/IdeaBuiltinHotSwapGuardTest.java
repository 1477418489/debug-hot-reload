package dev.hotreload.idea.run;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeaBuiltinHotSwapGuardTest {
    @AfterEach
    void cleanup() {
        IdeaBuiltinHotSwapGuard.resetForTest();
    }

    @Test
    void tracksActiveSessionCounter() {
        assertEquals(0, IdeaBuiltinHotSwapGuard.activeSessionsForTest());
        IdeaBuiltinHotSwapGuard.onSessionActivated(null, null, "x");
        assertEquals(0, IdeaBuiltinHotSwapGuard.activeSessionsForTest());
    }

    @Test
    void duplicateOrUnknownCloseCannotConsumeAnotherProjectsSession() {
        IdeaBuiltinHotSwapGuard.SessionCounts<String> counts =
                new IdeaBuiltinHotSwapGuard.SessionCounts<String>();
        assertEquals(1, counts.open("first"));
        assertEquals(2, counts.open("second"));

        assertFalse(counts.close("missing"));
        assertEquals(2, counts.total());
        assertTrue(counts.close("first"));
        assertEquals(1, counts.total());
        assertFalse(counts.close("first"));
        assertEquals(1, counts.total());
        assertTrue(counts.has("second"));
        assertEquals(1, counts.removeAll("second"));
        assertEquals(0, counts.total());
    }
}
