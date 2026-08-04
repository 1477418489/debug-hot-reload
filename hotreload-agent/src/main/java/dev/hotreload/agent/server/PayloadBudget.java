package dev.hotreload.agent.server;

import java.util.concurrent.atomic.AtomicLong;

/** Atomic retained encoded-request accounting shared by the reader and mutation worker. */
final class PayloadBudget {
    private final long limit;
    private final AtomicLong retained = new AtomicLong();

    PayloadBudget(long limit) {
        if (limit <= 0L) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
    }

    boolean tryReserve(long bytes) {
        if (bytes < 0L || bytes > limit) return false;
        while (true) {
            long current = retained.get();
            if (bytes > limit - current) return false;
            if (retained.compareAndSet(current, current + bytes)) return true;
        }
    }

    void release(long bytes) {
        if (bytes < 0L) throw new IllegalArgumentException("bytes must not be negative");
        if (bytes == 0L) return;
        while (true) {
            long current = retained.get();
            if (bytes > current) throw new IllegalStateException("payload budget underflow");
            if (retained.compareAndSet(current, current - bytes)) return;
        }
    }

    long getRetainedBytes() { return retained.get(); }
}
