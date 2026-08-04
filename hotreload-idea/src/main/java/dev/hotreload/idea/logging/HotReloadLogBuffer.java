package dev.hotreload.idea.logging;

import dev.hotreload.idea.settings.HotReloadSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bounded ring buffer for plugin tool-window logs.
 * Avoids O(n) {@code ArrayList.remove(0)} on capacity overflow.
 */
public final class HotReloadLogBuffer {
    public static final int DEFAULT_CAPACITY = HotReloadSettings.DEFAULT_LOG_CAPACITY;

    public interface Listener {
        void onAppend(HotReloadLogEvent event);
        void onCleared();
        default void onReset(List<HotReloadLogEvent> events) { }
    }

    private volatile int capacity;
    private HotReloadLogEvent[] ring;
    private int head;
    private int size;
    private final Object notificationLock = new Object();
    private final Object stateLock = new Object();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    public HotReloadLogBuffer() {
        this(configuredCapacity());
    }

    public HotReloadLogBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.ring = new HotReloadLogEvent[capacity];
    }

    public int capacity() {
        return capacity;
    }

    public void append(HotReloadLogEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (notificationLock) {
            synchronized (stateLock) {
                int index = (head + size) % capacity;
                if (size == capacity) {
                    ring[head] = event;
                    head = (head + 1) % capacity;
                } else {
                    ring[index] = event;
                    size++;
                }
            }
            for (Listener listener : listeners) {
                try {
                    listener.onAppend(event);
                } catch (RuntimeException ignored) {
                    // A tool-window listener must not break the operation that emitted the log.
                }
            }
        }
    }

    public List<HotReloadLogEvent> snapshot() {
        synchronized (notificationLock) {
            return snapshotContents();
        }
    }

    private List<HotReloadLogEvent> snapshotContents() {
        synchronized (stateLock) {
            List<HotReloadLogEvent> copy = new ArrayList<HotReloadLogEvent>(size);
            for (int i = 0; i < size; i++) {
                copy.add(ring[(head + i) % capacity]);
            }
            return List.copyOf(copy);
        }
    }

    public void clear() {
        synchronized (notificationLock) {
            synchronized (stateLock) {
                for (int i = 0; i < capacity; i++) ring[i] = null;
                head = 0;
                size = 0;
            }
            for (Listener listener : listeners) {
                try {
                    listener.onCleared();
                } catch (RuntimeException ignored) {
                    // Keep notifying the remaining listeners.
                }
            }
        }
    }

    public void setCapacity(int newCapacity) {
        if (newCapacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        synchronized (notificationLock) {
            List<HotReloadLogEvent> retained;
            synchronized (stateLock) {
                if (newCapacity == capacity) return;
                int retainedSize = Math.min(size, newCapacity);
                HotReloadLogEvent[] replacement = new HotReloadLogEvent[newCapacity];
                int start = (head + size - retainedSize) % capacity;
                for (int i = 0; i < retainedSize; i++) {
                    replacement[i] = ring[(start + i) % capacity];
                }
                capacity = newCapacity;
                ring = replacement;
                head = 0;
                size = retainedSize;
                retained = snapshotContents();
            }
            for (Listener listener : listeners) {
                try {
                    listener.onReset(retained);
                } catch (RuntimeException ignored) {
                    // Keep notifying the remaining listeners.
                }
            }
        }
    }

    public void refreshListeners() {
        synchronized (notificationLock) {
            List<HotReloadLogEvent> events = snapshotContents();
            for (Listener listener : listeners) {
                try {
                    listener.onReset(events);
                } catch (RuntimeException ignored) {
                    // Keep notifying the remaining listeners.
                }
            }
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    public List<HotReloadLogEvent> addListenerAndSnapshot(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (notificationLock) {
            listeners.addIfAbsent(listener);
            return snapshotContents();
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static int configuredCapacity() {
        try {
            return HotReloadSettings.getInstance().getLogCapacity();
        } catch (Throwable ignored) {
            return DEFAULT_CAPACITY;
        }
    }
}
