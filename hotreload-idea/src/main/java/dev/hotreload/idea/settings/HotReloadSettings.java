package dev.hotreload.idea.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "MyBatisDebugHotReloadSettings", storages = @Storage("mybatis-debug-hotreload.xml"))
public final class HotReloadSettings implements PersistentStateComponent<HotReloadSettings.State> {
    public static final int MIN_LOG_CAPACITY = 100;
    public static final int MAX_LOG_CAPACITY = 10_000;
    public static final int DEFAULT_LOG_CAPACITY = 1_000;

    public static final class State {
        /** 全局默认值；项目可选择继承、显式启用或显式禁用。 */
        public boolean pluginEnabled = true;
        public boolean javaReloadEnabled = true;
        public boolean mapperReloadEnabled = true;
        public boolean configReloadEnabled = true;
        public boolean staticResourceReloadEnabled = true;
        public boolean showVerboseLogs = false;
        public int logCapacity = DEFAULT_LOG_CAPACITY;
        /** Debug 启动时自动启用增强热更运行时参数（DCEVM altjvm / JBR enhanced redefinition）。 */
        public boolean enhancedRuntimeEnabled = true;
        /** 被本插件覆盖前的 IDEA 内置 HotSwap 设置值；持久化以支持 IDE 崩溃后恢复。null 表示未覆盖。 */
        public String previousRunHotSwap = null;

        public State() {
        }

        private State(State source) {
            pluginEnabled = source.pluginEnabled;
            javaReloadEnabled = source.javaReloadEnabled;
            mapperReloadEnabled = source.mapperReloadEnabled;
            configReloadEnabled = source.configReloadEnabled;
            staticResourceReloadEnabled = source.staticResourceReloadEnabled;
            showVerboseLogs = source.showVerboseLogs;
            logCapacity = source.logCapacity;
            enhancedRuntimeEnabled = source.enhancedRuntimeEnabled;
            previousRunHotSwap = source.previousRunHotSwap;
        }
    }

    private volatile State state = new State();

    public static HotReloadSettings getInstance() {
        return ApplicationManager.getApplication().getService(HotReloadSettings.class);
    }

    public boolean isShowVerboseLogs() {
        return current().showVerboseLogs;
    }

    public synchronized void setShowVerboseLogs(boolean value) {
        State next = copy();
        next.showVerboseLogs = value;
        state = next;
    }

    public boolean isPluginEnabled() {
        return current().pluginEnabled;
    }

    public synchronized void setPluginEnabled(boolean value) {
        State next = copy();
        next.pluginEnabled = value;
        state = next;
    }

    public boolean isJavaReloadEnabled() {
        return current().javaReloadEnabled;
    }

    public synchronized void setJavaReloadEnabled(boolean value) {
        State next = copy();
        next.javaReloadEnabled = value;
        state = next;
    }

    public boolean isMapperReloadEnabled() {
        return current().mapperReloadEnabled;
    }

    public synchronized void setMapperReloadEnabled(boolean value) {
        State next = copy();
        next.mapperReloadEnabled = value;
        state = next;
    }

    public boolean isConfigReloadEnabled() {
        return current().configReloadEnabled;
    }

    public synchronized void setConfigReloadEnabled(boolean value) {
        State next = copy();
        next.configReloadEnabled = value;
        state = next;
    }

    public boolean isStaticResourceReloadEnabled() {
        return current().staticResourceReloadEnabled;
    }

    public synchronized void setStaticResourceReloadEnabled(boolean value) {
        State next = copy();
        next.staticResourceReloadEnabled = value;
        state = next;
    }

    public boolean isEnhancedRuntimeEnabled() {
        return current().enhancedRuntimeEnabled;
    }

    public synchronized void setEnhancedRuntimeEnabled(boolean value) {
        State next = copy();
        next.enhancedRuntimeEnabled = value;
        state = next;
    }

    public int getLogCapacity() {
        return clampLogCapacity(current().logCapacity);
    }

    public synchronized void setLogCapacity(int value) {
        State next = copy();
        next.logCapacity = clampLogCapacity(value);
        state = next;
    }

    public String getPreviousRunHotSwap() {
        return current().previousRunHotSwap;
    }

    public synchronized void setPreviousRunHotSwap(String value) {
        State next = copy();
        next.previousRunHotSwap = value;
        state = next;
    }

    synchronized void updateUserOptions(boolean pluginEnabled,
                                        boolean javaReloadEnabled,
                                        boolean mapperReloadEnabled,
                                        boolean configReloadEnabled,
                                        boolean staticResourceReloadEnabled,
                                        boolean enhancedRuntimeEnabled,
                                        boolean showVerboseLogs,
                                        int logCapacity) {
        State next = copy();
        next.pluginEnabled = pluginEnabled;
        next.javaReloadEnabled = javaReloadEnabled;
        next.mapperReloadEnabled = mapperReloadEnabled;
        next.configReloadEnabled = configReloadEnabled;
        next.staticResourceReloadEnabled = staticResourceReloadEnabled;
        next.enhancedRuntimeEnabled = enhancedRuntimeEnabled;
        next.showVerboseLogs = showVerboseLogs;
        next.logCapacity = clampLogCapacity(logCapacity);
        state = next;
    }

    State snapshot() {
        return new State(current());
    }

    @Override
    public @Nullable State getState() {
        return snapshot();
    }

    @Override
    public synchronized void loadState(@NotNull State state) {
        State normalized = new State(state);
        normalized.logCapacity = clampLogCapacity(normalized.logCapacity);
        this.state = normalized;
    }

    public static int clampLogCapacity(int value) {
        return Math.max(MIN_LOG_CAPACITY, Math.min(MAX_LOG_CAPACITY, value));
    }

    private State current() {
        State value = state;
        return value == null ? new State() : value;
    }

    private State copy() {
        return new State(current());
    }
}
