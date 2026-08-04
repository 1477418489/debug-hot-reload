package dev.hotreload.idea.client;

import java.util.concurrent.ExecutorService;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Adds an explicit admission limit to an executor whose own work queue may be unbounded. */
final class BoundedResourceExecutor {
    private final ExecutorService delegate;
    private final int maximumPendingTasks;
    private final AtomicInteger pendingTasks = new AtomicInteger();

    BoundedResourceExecutor(ExecutorService delegate, int maximumPendingTasks) {
        if (delegate == null) throw new NullPointerException("delegate");
        if (maximumPendingTasks <= 0) {
            throw new IllegalArgumentException("maximumPendingTasks must be positive");
        }
        this.delegate = delegate;
        this.maximumPendingTasks = maximumPendingTasks;
    }

    boolean execute(Runnable task) {
        return execute(task, 1);
    }

    boolean execute(Runnable task, int workUnits) {
        if (task == null) throw new NullPointerException("task");
        if (workUnits <= 0) throw new IllegalArgumentException("workUnits must be positive");
        if (!reserve(workUnits)) return false;
        CountedTask counted = new CountedTask(task, workUnits);
        try {
            delegate.execute(counted);
            return true;
        } catch (RejectedExecutionException rejected) {
            pendingTasks.addAndGet(-workUnits);
            return false;
        }
    }

    void shutdownNow() {
        List<Runnable> dropped = delegate.shutdownNow();
        int droppedUnits = 0;
        for (Runnable task : dropped) {
            if (task instanceof CountedTask) {
                droppedUnits += ((CountedTask) task).workUnits;
            }
        }
        if (droppedUnits > 0) {
            final int released = droppedUnits;
            pendingTasks.updateAndGet(current -> Math.max(0, current - released));
        }
    }

    int pendingTasks() {
        return pendingTasks.get();
    }

    private boolean reserve(int workUnits) {
        while (true) {
            int current = pendingTasks.get();
            if (workUnits > maximumPendingTasks - current) return false;
            if (pendingTasks.compareAndSet(current, current + workUnits)) return true;
        }
    }

    private final class CountedTask implements Runnable {
        private final Runnable delegateTask;
        private final int workUnits;

        private CountedTask(Runnable delegateTask, int workUnits) {
            this.delegateTask = delegateTask;
            this.workUnits = workUnits;
        }

        @Override public void run() {
            try {
                delegateTask.run();
            } finally {
                pendingTasks.addAndGet(-workUnits);
            }
        }
    }
}
