package dev.hotreload.bootstrap;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

public final class WriteLockToken {
    private final Lock lock;
    private final AtomicBoolean released = new AtomicBoolean();

    WriteLockToken(Lock lock) {
        this.lock = lock;
    }

    void release() {
        if (released.compareAndSet(false, true)) lock.unlock();
    }
}
