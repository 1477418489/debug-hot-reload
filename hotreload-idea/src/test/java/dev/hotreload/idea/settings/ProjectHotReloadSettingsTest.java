package dev.hotreload.idea.settings;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectHotReloadSettingsTest {
    @Test void defaultsToGlobalInheritanceWithAllLocalFeaturesReady() {
        ProjectHotReloadSettings settings = new ProjectHotReloadSettings();

        assertEquals(ProjectHotReloadSettings.ActivationMode.INHERIT,
                settings.getActivationMode());
        assertTrue(settings.isJavaReloadEnabled());
        assertTrue(settings.isMapperReloadEnabled());
        assertTrue(settings.isConfigReloadEnabled());
        assertTrue(settings.isStaticResourceReloadEnabled());
        assertTrue(settings.isEnhancedRuntimeEnabled());
        assertTrue(settings.getExcludedRunConfigurations().isEmpty());
    }

    @Test void storesFeatureOverridesAndConfigurationExclusions() {
        ProjectHotReloadSettings settings = new ProjectHotReloadSettings();
        String excluded = ProjectHotReloadSettings.configurationKey("Application", "Demo");
        settings.setActivationMode(ProjectHotReloadSettings.ActivationMode.ENABLED);
        settings.setJavaReloadEnabled(false);
        settings.setMapperReloadEnabled(false);
        settings.setConfigReloadEnabled(false);
        settings.setStaticResourceReloadEnabled(false);
        settings.setEnhancedRuntimeEnabled(false);
        settings.setExcludedRunConfigurations(Arrays.asList(excluded, excluded, null, ""));

        assertEquals(ProjectHotReloadSettings.ActivationMode.ENABLED,
                settings.getActivationMode());
        assertFalse(settings.isJavaReloadEnabled());
        assertFalse(settings.isMapperReloadEnabled());
        assertFalse(settings.isConfigReloadEnabled());
        assertFalse(settings.isStaticResourceReloadEnabled());
        assertFalse(settings.isEnhancedRuntimeEnabled());
        assertTrue(settings.isRunConfigurationExcluded("Application", "Demo"));
        assertEquals(new LinkedHashSet<String>(Arrays.asList(excluded)),
                settings.getExcludedRunConfigurations());
    }

    @Test void loadStateFallsBackFromUnknownActivationModeAndNormalizesExclusions() {
        ProjectHotReloadSettings.State state = new ProjectHotReloadSettings.State();
        state.activationMode = "REMOVED_MODE";
        state.excludedRunConfigurations.add("saved");
        state.excludedRunConfigurations.add("");
        state.excludedRunConfigurations.add(null);
        ProjectHotReloadSettings settings = new ProjectHotReloadSettings();
        settings.loadState(state);
        state.excludedRunConfigurations.add("mutated");

        assertEquals(ProjectHotReloadSettings.ActivationMode.INHERIT,
                settings.getActivationMode());
        assertEquals(new LinkedHashSet<String>(Arrays.asList("saved")),
                settings.getExcludedRunConfigurations());
    }

    @Test void configurationKeySeparatesAmbiguousTypeAndNamePairs() {
        assertFalse(ProjectHotReloadSettings.configurationKey("AB", "C").equals(
                ProjectHotReloadSettings.configurationKey("A", "BC")));
    }

    @Test void bulkUserUpdateNormalizesExclusionsInOneState() {
        ProjectHotReloadSettings settings = new ProjectHotReloadSettings();
        String excluded = ProjectHotReloadSettings.configurationKey("Application", "Demo");

        settings.updateUserOptions(ProjectHotReloadSettings.ActivationMode.ENABLED,
                false, false, false, false, false,
                Arrays.asList(excluded, excluded, null, ""));

        assertEquals(ProjectHotReloadSettings.ActivationMode.ENABLED,
                settings.getActivationMode());
        assertFalse(settings.isJavaReloadEnabled());
        assertEquals(new LinkedHashSet<String>(Arrays.asList(excluded)),
                settings.getExcludedRunConfigurations());
    }

    @Test void persistedStateIsADeepSnapshot() {
        ProjectHotReloadSettings settings = new ProjectHotReloadSettings();
        ProjectHotReloadSettings.State exposed = settings.getState();
        exposed.activationMode = ProjectHotReloadSettings.ActivationMode.DISABLED.name();
        exposed.excludedRunConfigurations.add("mutated-outside");

        assertEquals(ProjectHotReloadSettings.ActivationMode.INHERIT,
                settings.getActivationMode());
        assertTrue(settings.getExcludedRunConfigurations().isEmpty());
    }
}
