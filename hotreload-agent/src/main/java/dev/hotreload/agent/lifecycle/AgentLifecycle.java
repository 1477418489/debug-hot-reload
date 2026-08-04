package dev.hotreload.agent.lifecycle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentLifecycle implements AutoCloseable {
    private final Object monitor = new Object();
    private final Deque<AutoCloseable> resources = new ArrayDeque<AutoCloseable>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger closeFailureCount = new AtomicInteger();

    public void register(AutoCloseable resource) {
        if (resource == null) throw new NullPointerException("resource");
        synchronized (monitor) {
            if (!closed.get()) {
                resources.push(resource);
                return;
            }
        }
        closeOne(resource);
    }

    public boolean isClosed() { return closed.get(); }
    public int getCloseFailureCount() { return closeFailureCount.get(); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        while (true) {
            AutoCloseable resource;
            synchronized (monitor) {
                resource = resources.pollFirst();
            }
            if (resource == null) return;
            closeOne(resource);
        }
    }

    private void closeOne(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            closeFailureCount.incrementAndGet();
        }
    }
}
