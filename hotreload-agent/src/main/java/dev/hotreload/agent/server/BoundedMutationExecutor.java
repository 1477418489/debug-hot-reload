package dev.hotreload.agent.server;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class BoundedMutationExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;

    BoundedMutationExecutor(int queueCapacity) {
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be positive");
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity), new NamedDaemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    boolean submit(Runnable mutation) {
        if (mutation == null) throw new NullPointerException("mutation");
        try {
            executor.execute(mutation);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    int getQueueSize() { return executor.getQueue().size(); }
    boolean isTerminated() { return executor.isTerminated(); }

    List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    boolean awaitTermination(long timeout, TimeUnit unit) {
        if (Thread.currentThread().getName().startsWith("hotreload-mutation-")) {
            return executor.isTerminated();
        }
        try {
            return executor.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }

    @Override public void close() {
        shutdownNow();
        awaitTermination(2, TimeUnit.SECONDS);
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "hotreload-mutation-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
