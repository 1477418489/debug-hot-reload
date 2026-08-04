package dev.hotreload.idea.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedResourceExecutorTest {
    @Test void rejectsBeyondTheAdmissionLimitAndReleasesCompletedTasks() {
        HoldingExecutor delegate = new HoldingExecutor();
        BoundedResourceExecutor executor = new BoundedResourceExecutor(delegate, 2);

        assertTrue(executor.execute(() -> { }));
        assertTrue(executor.execute(() -> { }));
        assertFalse(executor.execute(() -> { }));
        assertEquals(2, executor.pendingTasks());

        delegate.runNext();

        assertEquals(1, executor.pendingTasks());
        assertTrue(executor.execute(() -> { }));
    }

    @Test void releasesAdmissionWhenTheDelegateIsClosed() {
        HoldingExecutor delegate = new HoldingExecutor();
        delegate.shutdownNow();
        BoundedResourceExecutor executor = new BoundedResourceExecutor(delegate, 1);

        assertFalse(executor.execute(() -> { }));
        assertEquals(0, executor.pendingTasks());
    }

    @Test void shutdownReleasesTasksDroppedByTheDelegate() {
        HoldingExecutor delegate = new HoldingExecutor();
        BoundedResourceExecutor executor = new BoundedResourceExecutor(delegate, 2);
        assertTrue(executor.execute(() -> { }));
        assertTrue(executor.execute(() -> { }));

        executor.shutdownNow();

        assertEquals(0, executor.pendingTasks());
    }

    @Test void admitsAWeightedBatchCompletelyOrRejectsItWithoutReservation() {
        HoldingExecutor delegate = new HoldingExecutor();
        BoundedResourceExecutor executor = new BoundedResourceExecutor(delegate, 3);

        assertTrue(executor.execute(() -> { }, 2));
        assertFalse(executor.execute(() -> { }, 2));
        assertEquals(2, executor.pendingTasks());
        assertTrue(executor.execute(() -> { }, 1));
        assertEquals(3, executor.pendingTasks());

        delegate.runNext();
        assertEquals(1, executor.pendingTasks());
    }

    private static final class HoldingExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<Runnable>();
        private boolean shutdown;

        @Override public void shutdown() {
            shutdown = true;
        }

        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> dropped = new java.util.ArrayList<Runnable>(tasks);
            tasks.clear();
            return dropped;
        }

        @Override public boolean isShutdown() {
            return shutdown;
        }

        @Override public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove().run();
        }
    }
}
