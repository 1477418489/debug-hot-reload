package dev.hotreload.agent;

import java.util.concurrent.atomic.AtomicBoolean;

final class AgentSessionGuard {
    private final AtomicBoolean owned = new AtomicBoolean();

    boolean tryAcquire() {
        return owned.compareAndSet(false, true);
    }

    void release() {
        owned.set(false);
    }
}
