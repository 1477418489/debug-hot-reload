package dev.hotreload.bootstrap;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.LinkedHashMap;
import java.util.Map;

final class ConfigurationState {
    private static final int MAX_RESOURCES = 2048;

    private volatile String factoryClassName;
    private volatile boolean active;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final AtomicBoolean reloadUnsafe = new AtomicBoolean();
    private final Map<String, ResourceMetadata> resources = new LinkedHashMap<String, ResourceMetadata>();

    ConfigurationState(String factoryClassName, boolean active) {
        this.factoryClassName = factoryClassName;
        this.active = active;
    }

    String getFactoryClassName() { return factoryClassName; }
    boolean isActive() { return active; }
    void activate(String factoryClassName) {
        this.factoryClassName = factoryClassName;
        this.active = true;
    }
    ReentrantReadWriteLock getLock() { return lock; }
    boolean isReloadUnsafe() { return reloadUnsafe.get(); }
    void markReloadUnsafe() { reloadUnsafe.set(true); }

    synchronized boolean putResourceMetadata(ResourceMetadata metadata) {
        if (!resources.containsKey(metadata.getRuntimeResource()) && resources.size() >= MAX_RESOURCES) {
            reloadUnsafe.set(true);
            return false;
        }
        resources.put(metadata.getRuntimeResource(), metadata);
        return true;
    }

    synchronized ResourceMetadata getResourceMetadata(String runtimeResource) {
        return resources.get(runtimeResource);
    }

    synchronized java.util.List<ResourceMetadata> snapshotResourceMetadata() {
        return new java.util.ArrayList<ResourceMetadata>(resources.values());
    }
}
