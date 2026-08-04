package dev.hotreload.idea.change;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFileChangeListenerTest {
    @Test void acceptsSpringConfigNamesOutsideWebAndTemplateRoots() {
        assertTrue(ConfigFileChangeListener.isReloadableConfigPath("application.yml"));
        assertTrue(ConfigFileChangeListener.isReloadableConfigPath(
                "config/application-local.properties"));
        assertTrue(ConfigFileChangeListener.isReloadableConfigPath(
                "custom/bootstrap-dev.yaml"));
    }

    @Test void excludesConfigShapedFilesOwnedByOtherResourcePipelines() {
        assertFalse(ConfigFileChangeListener.isReloadableConfigPath(
                "static/application.properties"));
        assertFalse(ConfigFileChangeListener.isReloadableConfigPath(
                "public/bootstrap.yml"));
        assertFalse(ConfigFileChangeListener.isReloadableConfigPath(
                "resources/application.properties"));
        assertFalse(ConfigFileChangeListener.isReloadableConfigPath(
                "META-INF/resources/application.yml"));
        assertFalse(ConfigFileChangeListener.isReloadableConfigPath(
                "templates/application.yml"));
    }
}
