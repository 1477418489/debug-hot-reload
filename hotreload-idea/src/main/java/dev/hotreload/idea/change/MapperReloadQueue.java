package dev.hotreload.idea.change;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return scheduleAll(Collections.singletonList(launchId), sourceRoot, outputRoot, file);
    }

    /** Admits all matching Debug sessions atomically so one save cannot update only a subset. */
    public synchronized boolean scheduleAll(Iterable<String> launchIds, Path sourceRoot,
                                            Path outputRoot, Path file) {
        if (launchIds == null || sourceRoot == null || outputRoot == null || file == null) {
            throw new NullPointerException("launchIds and paths are required");
        }
        if (closed) return false;
        Set<String> uniqueLaunchIds = new LinkedHashSet<String>();
        for (String launchId : launchIds) {
            if (launchId == null || launchId.isEmpty()) {
                throw new NullPointerException("launchId is required");
            }
            uniqueLaunchIds.add(launchId);
        }
        if (uniqueLaunchIds.isEmpty()) return false;
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
        List<QueueKey> queueKeys = new ArrayList<QueueKey>(uniqueLaunchIds.size());
        int newEntries = 0;
        for (String launchId : uniqueLaunchIds) {
            QueueKey queueKey = new QueueKey(launchId, canonicalSource, canonicalOutput, key);
            queueKeys.add(queueKey);
            if (!pending.containsKey(queueKey)) newEntries++;
        }
        if (newEntries > capacity - pending.size()) return false;

        Map<QueueKey, Entry> staged = new LinkedHashMap<QueueKey, Entry>();
        try {
            int index = 0;
            for (String launchId : uniqueLaunchIds) {
                QueueKey queueKey = queueKeys.get(index++);
                Entry entry = new Entry(launchId, canonicalSource, canonicalOutput, key, queueKey);
                entry.future = scheduler.schedule(() -> fire(entry), delayMillis,
                        TimeUnit.MILLISECONDS);
                staged.put(queueKey, entry);
            }
        } catch (RejectedExecutionException rejected) {
            cancel(staged.values());
            return false;
        } catch (RuntimeException rejected) {
            cancel(staged.values());
            return false;
        }
        for (Map.Entry<QueueKey, Entry> stagedEntry : staged.entrySet()) {
            Entry previous = pending.put(stagedEntry.getKey(), stagedEntry.getValue());
            if (previous != null && previous.future != null) previous.future.cancel(false);
        }
        return true;
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

    private static void cancel(Iterable<Entry> entries) {
        for (Entry entry : entries) {
            if (entry != null && entry.future != null) entry.future.cancel(false);
        }
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
        private final String launchId;
        private final Path sourceRoot;
        private final Path outputRoot;
        private final Path file;

        private QueueKey(String launchId, Path sourceRoot, Path outputRoot, Path file) {
            this.launchId = launchId;
            this.sourceRoot = sourceRoot;
            this.outputRoot = outputRoot;
            this.file = file;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof QueueKey)) return false;
            QueueKey that = (QueueKey) other;
            return launchId.equals(that.launchId)
                    && sourceRoot.equals(that.sourceRoot)
                    && outputRoot.equals(that.outputRoot)
                    && file.equals(that.file);
        }

        @Override public int hashCode() {
            int result = launchId.hashCode();
            result = 31 * result + sourceRoot.hashCode();
            result = 31 * result + outputRoot.hashCode();
            return 31 * result + file.hashCode();
        }
    }
}
