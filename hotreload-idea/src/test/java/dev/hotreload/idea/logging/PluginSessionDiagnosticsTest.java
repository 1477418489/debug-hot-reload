package dev.hotreload.idea.logging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSessionDiagnosticsTest {
    @Test
    void infoAndWarnPublishToBuffer() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(10);
        PluginSessionDiagnostics diagnostics = new PluginSessionDiagnostics("proj", buffer);

        diagnostics.info("SESSION_ACTIVE", "launch-1", "targetJdk", "21");
        diagnostics.warn("CLASS_BATCH_SKIPPED", null, "reason", "no_active_session");

        List<HotReloadLogEvent> events = buffer.snapshot();
        assertEquals(2, events.size());
        assertEquals(HotReloadLogEvent.Level.INFO, events.get(0).getLevel());
        assertEquals("SESSION_ACTIVE", events.get(0).getEvent());
        assertEquals("launch-1", events.get(0).getLaunchId());
        assertTrue(events.get(0).getDetails().contains("targetJdk=21"));
        assertEquals(HotReloadLogEvent.Level.WARN, events.get(1).getLevel());
        assertEquals("none", events.get(1).getLaunchId());
        assertTrue(events.get(1).getDetails().contains("reason=no_active_session"));
    }

    @Test
    void sanitizesWhitespaceInFieldValues() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(10);
        PluginSessionDiagnostics diagnostics = new PluginSessionDiagnostics("proj", buffer);
        diagnostics.info("XML_RELOAD_SEND", "l1", "resourceId", "mapper/User Mapper.xml");
        assertTrue(buffer.snapshot().get(0).getDetails().contains("resourceId=mapper/User_Mapper.xml"));
    }

    @Test
    void keepsSkippedCountWithOtherPreferredResultFields() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(10);
        PluginSessionDiagnostics diagnostics = new PluginSessionDiagnostics("proj", buffer);
        diagnostics.info("CLASS_BATCH_RESULT", "l1",
                "failedCount", "0", "skippedCount", "2", "successCount", "1");

        assertTrue(buffer.snapshot().get(0).getDetails().contains("successCount=1 skippedCount=2 failedCount=0"));
    }
}
