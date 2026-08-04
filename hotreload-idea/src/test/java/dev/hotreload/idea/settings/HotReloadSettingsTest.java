package dev.hotreload.idea.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotReloadSettingsTest {
    @Test void defaultsPreserveExistingPluginBehavior() {
        HotReloadSettings settings = new HotReloadSettings();

        assertTrue(settings.isPluginEnabled());
        assertTrue(settings.isJavaReloadEnabled());
        assertTrue(settings.isMapperReloadEnabled());
        assertTrue(settings.isConfigReloadEnabled());
        assertTrue(settings.isStaticResourceReloadEnabled());
        assertTrue(settings.isEnhancedRuntimeEnabled());
        assertFalse(settings.isShowVerboseLogs());
        assertEquals(HotReloadSettings.DEFAULT_LOG_CAPACITY, settings.getLogCapacity());
        assertNull(settings.getPreviousRunHotSwap());
    }

    @Test void togglesAllUserFacingOptions() {
        HotReloadSettings settings = new HotReloadSettings();
        settings.setPluginEnabled(false);
        settings.setJavaReloadEnabled(false);
        settings.setMapperReloadEnabled(false);
        settings.setConfigReloadEnabled(false);
        settings.setStaticResourceReloadEnabled(false);
        settings.setEnhancedRuntimeEnabled(false);
        settings.setShowVerboseLogs(true);
        settings.setLogCapacity(2_500);

        assertFalse(settings.isPluginEnabled());
        assertFalse(settings.isJavaReloadEnabled());
        assertFalse(settings.isMapperReloadEnabled());
        assertFalse(settings.isConfigReloadEnabled());
        assertFalse(settings.isStaticResourceReloadEnabled());
        assertFalse(settings.isEnhancedRuntimeEnabled());
        assertTrue(settings.isShowVerboseLogs());
        assertEquals(2_500, settings.getLogCapacity());
    }

    @Test void loadStateCopiesAndNormalizesPersistedValues() {
        HotReloadSettings.State persisted = new HotReloadSettings.State();
        persisted.pluginEnabled = false;
        persisted.showVerboseLogs = true;
        persisted.logCapacity = Integer.MAX_VALUE;
        persisted.previousRunHotSwap = "always";

        HotReloadSettings settings = new HotReloadSettings();
        settings.loadState(persisted);
        persisted.pluginEnabled = true;
        persisted.previousRunHotSwap = "mutated";

        assertFalse(settings.isPluginEnabled());
        assertTrue(settings.isShowVerboseLogs());
        assertEquals(HotReloadSettings.MAX_LOG_CAPACITY, settings.getLogCapacity());
        assertEquals("always", settings.getPreviousRunHotSwap());
    }

    @Test void clampsLogCapacityAtBothBounds() {
        HotReloadSettings settings = new HotReloadSettings();
        settings.setLogCapacity(0);
        assertEquals(HotReloadSettings.MIN_LOG_CAPACITY, settings.getLogCapacity());
        settings.setLogCapacity(Integer.MAX_VALUE);
        assertEquals(HotReloadSettings.MAX_LOG_CAPACITY, settings.getLogCapacity());
    }

    @Test void bulkUserUpdatePreservesInternalHotSwapRecoveryValue() {
        HotReloadSettings settings = new HotReloadSettings();
        settings.setPreviousRunHotSwap("ask");

        settings.updateUserOptions(false, false, false, false, false,
                false, true, 2_000);

        assertFalse(settings.isPluginEnabled());
        assertFalse(settings.isJavaReloadEnabled());
        assertTrue(settings.isShowVerboseLogs());
        assertEquals(2_000, settings.getLogCapacity());
        assertEquals("ask", settings.getPreviousRunHotSwap());
    }

    @Test void persistedStateIsADefensiveSnapshot() {
        HotReloadSettings settings = new HotReloadSettings();
        HotReloadSettings.State exposed = settings.getState();
        exposed.pluginEnabled = false;
        exposed.previousRunHotSwap = "mutated-outside";

        assertTrue(settings.isPluginEnabled());
        assertNull(settings.getPreviousRunHotSwap());
    }
}
