package dev.hotreload.idea.settings;

import com.intellij.openapi.project.Project;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class HotReloadSettingsResolver {
    public enum Feature {
        JAVA,
        MAPPER,
        CONFIG,
        STATIC_RESOURCE
    }

    private HotReloadSettingsResolver() {
    }

    public static Snapshot resolve(Project project) {
        if (project == null) throw new NullPointerException("project");
        HotReloadSettings global = HotReloadSettings.getInstance();
        ProjectHotReloadSettings local = ProjectHotReloadSettings.getInstance(project);
        return resolve(global, local);
    }

    static Snapshot resolve(HotReloadSettings global, ProjectHotReloadSettings local) {
        if (global == null) throw new NullPointerException("global");
        if (local == null) throw new NullPointerException("local");

        HotReloadSettings.State globalState = global.snapshot();
        ProjectHotReloadSettings.State localState = local.snapshot();
        ProjectHotReloadSettings.ActivationMode mode =
                ProjectHotReloadSettings.parseActivationMode(localState.activationMode);
        boolean enabled = mode == ProjectHotReloadSettings.ActivationMode.ENABLED
                || (mode == ProjectHotReloadSettings.ActivationMode.INHERIT
                && globalState.pluginEnabled);
        boolean useProjectValues = mode == ProjectHotReloadSettings.ActivationMode.ENABLED;

        boolean javaReload = enabled && (useProjectValues
                ? localState.javaReloadEnabled : globalState.javaReloadEnabled);
        boolean mapperReload = enabled && (useProjectValues
                ? localState.mapperReloadEnabled : globalState.mapperReloadEnabled);
        boolean configReload = enabled && (useProjectValues
                ? localState.configReloadEnabled : globalState.configReloadEnabled);
        boolean staticReload = enabled && (useProjectValues
                ? localState.staticResourceReloadEnabled
                : globalState.staticResourceReloadEnabled);
        boolean enhancedRuntime = javaReload && (useProjectValues
                ? localState.enhancedRuntimeEnabled : globalState.enhancedRuntimeEnabled);
        Set<String> exclusions = localState.excludedRunConfigurations == null
                ? Collections.<String>emptySet() : localState.excludedRunConfigurations;

        return new Snapshot(enabled, javaReload, mapperReload, configReload, staticReload,
                enhancedRuntime, exclusions);
    }

    public static final class Snapshot {
        private final boolean projectEnabled;
        private final boolean javaReloadEnabled;
        private final boolean mapperReloadEnabled;
        private final boolean configReloadEnabled;
        private final boolean staticResourceReloadEnabled;
        private final boolean enhancedRuntimeEnabled;
        private final Set<String> excludedRunConfigurations;

        private Snapshot(boolean projectEnabled, boolean javaReloadEnabled,
                         boolean mapperReloadEnabled, boolean configReloadEnabled,
                         boolean staticResourceReloadEnabled, boolean enhancedRuntimeEnabled,
                         Set<String> excludedRunConfigurations) {
            this.projectEnabled = projectEnabled;
            this.javaReloadEnabled = javaReloadEnabled;
            this.mapperReloadEnabled = mapperReloadEnabled;
            this.configReloadEnabled = configReloadEnabled;
            this.staticResourceReloadEnabled = staticResourceReloadEnabled;
            this.enhancedRuntimeEnabled = enhancedRuntimeEnabled;
            this.excludedRunConfigurations = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(excludedRunConfigurations));
        }

        public boolean isProjectEnabled() { return projectEnabled; }
        public boolean isJavaReloadEnabled() { return javaReloadEnabled; }
        public boolean isMapperReloadEnabled() { return mapperReloadEnabled; }
        public boolean isConfigReloadEnabled() { return configReloadEnabled; }
        public boolean isStaticResourceReloadEnabled() { return staticResourceReloadEnabled; }
        public boolean isEnhancedRuntimeEnabled() { return enhancedRuntimeEnabled; }

        public boolean isFeatureEnabled(Feature feature) {
            if (feature == null) return false;
            switch (feature) {
                case JAVA: return javaReloadEnabled;
                case MAPPER: return mapperReloadEnabled;
                case CONFIG: return configReloadEnabled;
                case STATIC_RESOURCE: return staticResourceReloadEnabled;
                default: return false;
            }
        }

        public boolean hasAnyReloadFeatureEnabled() {
            return javaReloadEnabled || mapperReloadEnabled || configReloadEnabled
                    || staticResourceReloadEnabled;
        }

        public boolean isRunConfigurationEnabled(String configurationTypeId,
                                                 String configurationName) {
            return projectEnabled && !excludedRunConfigurations.contains(
                    ProjectHotReloadSettings.configurationKey(
                            configurationTypeId, configurationName));
        }
    }
}
