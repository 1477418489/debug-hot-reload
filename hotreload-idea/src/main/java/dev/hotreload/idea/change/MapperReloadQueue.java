package dev.hotreload.idea.change;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class MapperReloadQueue implements AutoCloseable {
    public interface Handler {
        void reload(String launchId, Path sourceRoot, Path outputRoot, Path file);
    }

    private final ScheduledExecutorService scheduler;
    private final long delayMillis;
    private final int capacity;
    private final Handler handler;
    /**
     * A source file can feed more than one compiler output in a project with multiple Debug
     * sessions.  Coalescing by the source path alone would silently drop one of those updates.
     */
    private final Map<QueueKey, Entry> pending = new HashMap<QueueKey, Entry>();
    private boolean closed;

    public MapperReloadQueue(ScheduledExecutorService scheduler, long delayMillis, int capacity, Handler handler) {
        if (scheduler == null) throw new NullPointerException("scheduler");
        if (handler == null) throw new NullPointerException("handler");
        if (delayMillis < 0 || capacity <= 0) throw new IllegalArgumentException("invalid queue limits");
        this.scheduler = scheduler;
        this.delayMillis = delayMillis;
        this.capacity = capacity;
        this.handler = handler;
    }

    public synchronized boolean schedule(String launchId, Path sourceRoot, Path outputRoot, Path file) {
        if (launchId == null || launchId.isEmpty()
                || sourceRoot == null || outputRoot == null || file == null) {
            throw new NullPointerException("launchId and paths are required");
        }
        if (closed) return false;
        final Path canonicalSource;
        final Path canonicalOutput;
        final Path key;
        try {
            canonicalSource = PathSafety.schedulingDirectory(sourceRoot);
            canonicalOutput = PathSafety.schedulingDirectory(outputRoot);
            key = PathSafety.schedulingFile(canonicalSource, file);
        } catch (Exception e) {
            return false;
        }
        QueueKey queueKey = new QueueKey(canonicalSource, canonicalOutput, key);
        Entry previous = pending.get(queueKey);
        if (previous == null && pending.size() >= capacity) return false;
        if (previous != null) previous.future.cancel(false);

        Entry entry = new Entry(launchId, canonicalSource, canonicalOutput, key, queueKey);
        pending.put(queueKey, entry);
        try {
            entry.future = scheduler.schedule(() -> fire(entry), delayMillis,
                    TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException rejected) {
            if (pending.get(queueKey) == entry) pending.remove(queueKey);
            return false;
        }
    }

    public synchronized int getPendingCount() {
        return pending.size();
    }

    private void fire(Entry entry) {
        synchronized (this) {
            if (closed || pending.get(entry.key) != entry) return;
            pending.remove(entry.key);
        }
        handler.reload(entry.launchId, entry.sourceRoot, entry.outputRoot, entry.file);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Entry entry : pending.values()) {
            if (entry.future != null) entry.future.cancel(false);
        }
        pending.clear();
    }

    private static final class Entry {
        private final String launchId;
        private final Path sourceRoot;
        private final Path outputRoot;
        private final Path file;
        private final QueueKey key;
        private ScheduledFuture<?> future;

        private Entry(String launchId, Path sourceRoot, Path outputRoot, Path file,
                      QueueKey key) {
            this.launchId = launchId;
            this.sourceRoot = sourceRoot;
            this.outputRoot = outputRoot;
            this.file = file;
            this.key = key;
        }
    }

    private static final class QueueKey {
        private final Path sourceRoot;
        private final Path outputRoot;
        private final Path file;

        private QueueKey(Path sourceRoot, Path outputRoot, Path file) {
            this.sourceRoot = sourceRoot;
            this.outputRoot = outputRoot;
            this.file = file;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof QueueKey)) return false;
            QueueKey that = (QueueKey) other;
            return sourceRoot.equals(that.sourceRoot)
                    && outputRoot.equals(that.outputRoot)
                    && file.equals(that.file);
        }

        @Override public int hashCode() {
            int result = sourceRoot.hashCode();
            result = 31 * result + outputRoot.hashCode();
            return 31 * result + file.hashCode();
        }
    }
}
