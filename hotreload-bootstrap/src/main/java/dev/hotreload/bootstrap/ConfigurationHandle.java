package dev.hotreload.bootstrap;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

public final class ConfigurationHandle {
    private final WeakReference<Object> configuration;
    private final ConfigurationState state;

    ConfigurationHandle(Object configuration, ConfigurationState state) {
        this.configuration = new WeakReference<Object>(configuration);
        this.state = state;
    }

    public Object getConfiguration() { return configuration.get(); }
    public String getFactoryClassName() { return state.getFactoryClassName(); }
    public boolean isReloadUnsafe() { return state.isReloadUnsafe(); }
    public ResourceMetadata getResourceMetadata(String runtimeResource) {
        return state.getResourceMetadata(runtimeResource);
    }
    public List<ResourceMetadata> getResourceMetadata() {
        return Collections.unmodifiableList(state.snapshotResourceMetadata());
    }
}
