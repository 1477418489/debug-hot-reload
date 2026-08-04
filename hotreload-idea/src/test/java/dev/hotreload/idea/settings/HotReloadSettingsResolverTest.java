package dev.hotreload.idea.settings;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotReloadSettingsResolverTest {
    @Test void inheritedProjectUsesGlobalDefaults() {
        HotReloadSettings global = new HotReloadSettings();
        ProjectHotReloadSettings local = new ProjectHotReloadSettings();
        global.setMapperReloadEnabled(false);

        HotReloadSettingsResolver.Snapshot snapshot =
                HotReloadSettingsResolver.resolve(global, local);

        assertTrue(snapshot.isProjectEnabled());
        assertTrue(snapshot.isJavaReloadEnabled());
        assertFalse(snapshot.isMapperReloadEnabled());
        assertTrue(snapshot.isEnhancedRuntimeEnabled());
    }

    @Test void explicitProjectEnableOverridesDisabledGlobalDefault() {
        HotReloadSettings global = new HotReloadSettings();
        global.setPluginEnabled(false);
        ProjectHotReloadSettings local = new ProjectHotReloadSettings();
        local.setActivationMode(ProjectHotReloadSettings.ActivationMode.ENABLED);
        local.setJavaReloadEnabled(false);

        HotReloadSettingsResolver.Snapshot snapshot =
                HotReloadSettingsResolver.resolve(global, local);

        assertTrue(snapshot.isProjectEnabled());
        assertFalse(snapshot.isJavaReloadEnabled());
        assertTrue(snapshot.isMapperReloadEnabled());
        assertFalse(snapshot.isEnhancedRuntimeEnabled());
    }

    @Test void explicitDisableTurnsOffEveryFeature() {
        HotReloadSettings global = new HotReloadSettings();
        ProjectHotReloadSettings local = new ProjectHotReloadSettings();
        local.setActivationMode(ProjectHotReloadSettings.ActivationMode.DISABLED);

        HotReloadSettingsResolver.Snapshot snapshot =
                HotReloadSettingsResolver.resolve(global, local);

        assertFalse(snapshot.isProjectEnabled());
        assertFalse(snapshot.hasAnyReloadFeatureEnabled());
        assertFalse(snapshot.isEnhancedRuntimeEnabled());
    }

    @Test void excludesOnlyTheSelectedRunConfiguration() {
        HotReloadSettings global = new HotReloadSettings();
        ProjectHotReloadSettings local = new ProjectHotReloadSettings();
        local.setExcludedRunConfigurations(Collections.singleton(
                ProjectHotReloadSettings.configurationKey("Application", "Blocked")));

        HotReloadSettingsResolver.Snapshot snapshot =
                HotReloadSettingsResolver.resolve(global, local);

        assertFalse(snapshot.isRunConfigurationEnabled("Application", "Blocked"));
        assertTrue(snapshot.isRunConfigurationEnabled("Application", "Allowed"));
        assertTrue(snapshot.isRunConfigurationEnabled(
                "SpringBootApplicationConfigurationType", "Blocked"));
    }
}
