package dev.hotreload.idea.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@State(name = "DebugHotReloadProjectSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ProjectHotReloadSettings
        implements PersistentStateComponent<ProjectHotReloadSettings.State> {
    public enum ActivationMode {
        INHERIT("继承全局"),
        ENABLED("启用"),
        DISABLED("禁用");

        private final String label;

        ActivationMode(String label) {
            this.label = label;
        }

        @Override public String toString() {
            return label;
        }
    }

    public static final class State {
        public String activationMode = ActivationMode.INHERIT.name();
        public boolean javaReloadEnabled = true;
        public boolean mapperReloadEnabled = true;
        public boolean configReloadEnabled = true;
        public boolean staticResourceReloadEnabled = true;
        public boolean enhancedRuntimeEnabled = true;
        public Set<String> excludedRunConfigurations = new LinkedHashSet<String>();

        public State() {
        }

        private State(State source) {
            activationMode = source.activationMode;
            javaReloadEnabled = source.javaReloadEnabled;
            mapperReloadEnabled = source.mapperReloadEnabled;
            configReloadEnabled = source.configReloadEnabled;
            staticResourceReloadEnabled = source.staticResourceReloadEnabled;
            enhancedRuntimeEnabled = source.enhancedRuntimeEnabled;
            excludedRunConfigurations = normalizedExclusions(source.excludedRunConfigurations);
        }
    }

    private volatile State state = new State();

    public static ProjectHotReloadSettings getInstance(Project project) {
        if (project == null) throw new NullPointerException("project");
        return project.getService(ProjectHotReloadSettings.class);
    }

    public ActivationMode getActivationMode() {
        String value = current().activationMode;
        try {
            return ActivationMode.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException ignored) {
            return ActivationMode.INHERIT;
        }
    }

    public synchronized void setActivationMode(ActivationMode value) {
        State next = copy();
        next.activationMode = (value == null ? ActivationMode.INHERIT : value).name();
        state = next;
    }

    public boolean isJavaReloadEnabled() { return current().javaReloadEnabled; }
    public boolean isMapperReloadEnabled() { return current().mapperReloadEnabled; }
    public boolean isConfigReloadEnabled() { return current().configReloadEnabled; }
    public boolean isStaticResourceReloadEnabled() { return current().staticResourceReloadEnabled; }
    public boolean isEnhancedRuntimeEnabled() { return current().enhancedRuntimeEnabled; }

    public synchronized void setJavaReloadEnabled(boolean value) {
        State next = copy();
        next.javaReloadEnabled = value;
        state = next;
    }

    public synchronized void setMapperReloadEnabled(boolean value) {
        State next = copy();
        next.mapperReloadEnabled = value;
        state = next;
    }

    public synchronized void setConfigReloadEnabled(boolean value) {
        State next = copy();
        next.configReloadEnabled = value;
        state = next;
    }

    public synchronized void setStaticResourceReloadEnabled(boolean value) {
        State next = copy();
        next.staticResourceReloadEnabled = value;
        state = next;
    }

    public synchronized void setEnhancedRuntimeEnabled(boolean value) {
        State next = copy();
        next.enhancedRuntimeEnabled = value;
        state = next;
    }

    public Set<String> getExcludedRunConfigurations() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(
                current().excludedRunConfigurations));
    }

    public synchronized void setExcludedRunConfigurations(Collection<String> values) {
        State next = copy();
        next.excludedRunConfigurations = normalizedExclusions(values);
        state = next;
    }

    synchronized void updateUserOptions(ActivationMode activationMode,
                                        boolean javaReloadEnabled,
                                        boolean mapperReloadEnabled,
                                        boolean configReloadEnabled,
                                        boolean staticResourceReloadEnabled,
                                        boolean enhancedRuntimeEnabled,
                                        Collection<String> excludedRunConfigurations) {
        State next = copy();
        next.activationMode = (activationMode == null
                ? ActivationMode.INHERIT : activationMode).name();
        next.javaReloadEnabled = javaReloadEnabled;
        next.mapperReloadEnabled = mapperReloadEnabled;
        next.configReloadEnabled = configReloadEnabled;
        next.staticResourceReloadEnabled = staticResourceReloadEnabled;
        next.enhancedRuntimeEnabled = enhancedRuntimeEnabled;
        next.excludedRunConfigurations = normalizedExclusions(excludedRunConfigurations);
        state = next;
    }

    State snapshot() {
        return new State(current());
    }

    public boolean isRunConfigurationExcluded(String configurationTypeId, String configurationName) {
        return current().excludedRunConfigurations.contains(
                configurationKey(configurationTypeId, configurationName));
    }

    public static String configurationKey(String configurationTypeId, String configurationName) {
        String type = configurationTypeId == null ? "" : configurationTypeId;
        String name = configurationName == null ? "" : configurationName;
        return type.length() + ":" + type + name;
    }

    @Override public @Nullable State getState() {
        return snapshot();
    }

    @Override public synchronized void loadState(@NotNull State state) {
        State normalized = new State(state);
        normalized.activationMode = parseActivationMode(normalized.activationMode).name();
        this.state = normalized;
    }

    static ActivationMode parseActivationMode(String value) {
        try {
            return ActivationMode.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException ignored) {
            return ActivationMode.INHERIT;
        }
    }

    private State current() {
        State value = state;
        return value == null ? new State() : value;
    }

    private State copy() {
        return new State(current());
    }

    private static Set<String> normalizedExclusions(Collection<String> values) {
        Set<String> normalized = new LinkedHashSet<String>();
        if (values == null) return normalized;
        for (String value : values) {
            if (value != null && !value.isEmpty()) normalized.add(value);
        }
        return normalized;
    }
}
