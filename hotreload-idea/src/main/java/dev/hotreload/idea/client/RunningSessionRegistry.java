package dev.hotreload.idea.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RunningSessionRegistry implements AutoCloseable {
    private final int capacity;
    private final Map<String, HotReloadClient> clients = new LinkedHashMap<String, HotReloadClient>();
    private boolean closed;

    RunningSessionRegistry(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    synchronized boolean add(String launchId, HotReloadClient client) {
        if (launchId == null || client == null) throw new NullPointerException("session is required");
        if (closed || clients.containsKey(launchId) || clients.size() >= capacity) return false;
        clients.put(launchId, client);
        return true;
    }

    synchronized Session only() {
        if (clients.size() != 1) return null;
        Map.Entry<String, HotReloadClient> entry = clients.entrySet().iterator().next();
        return new Session(entry.getKey(), entry.getValue());
    }

    synchronized Session get(String launchId) {
        if (launchId == null) return null;
        HotReloadClient client = clients.get(launchId);
        return client == null ? null : new Session(launchId, client);
    }

    synchronized List<Session> snapshot() {
        List<Session> sessions = new ArrayList<Session>(clients.size());
        for (Map.Entry<String, HotReloadClient> entry : clients.entrySet()) {
            sessions.add(new Session(entry.getKey(), entry.getValue()));
        }
        return sessions;
    }

    synchronized HotReloadClient remove(String launchId) {
        return clients.remove(launchId);
    }

    synchronized int size() {
        return clients.size();
    }

    @Override public void close() {
        List<HotReloadClient> snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = new ArrayList<HotReloadClient>(clients.values());
            clients.clear();
        }
        for (HotReloadClient client : snapshot) client.close();
    }

    static final class Session {
        private final String launchId;
        private final HotReloadClient client;

        private Session(String launchId, HotReloadClient client) {
            this.launchId = launchId;
            this.client = client;
        }

        String getLaunchId() { return launchId; }
        HotReloadClient getClient() { return client; }
    }
}
