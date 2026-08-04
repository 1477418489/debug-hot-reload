package dev.hotreload.idea.change;

import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;
import dev.hotreload.idea.client.HotReloadProjectService;
import dev.hotreload.protocol.ProtocolLimits;
import dev.hotreload.protocol.message.ClassUpdate;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Captures one immutable generation batch at each completion callback. IntelliJ's
 * fileGenerated callback has no CompileContext, so independently active compiler
 * callback threads retain separate collectors until their completion callback.
 */
public final class ClassCompilationListener implements CompilationStatusListener {
    private static final int MAX_ACTIVE_BATCHES = 16;
    private static final int MAX_LAUNCHES_PER_BATCH = 16;

    interface SessionOperations {
        boolean isJavaReloadEnabled();
        String activeDebugLaunchForOutput(Path outputRoot);
        default String activeDebugLaunchForClass(Path outputRoot, String relativePath) {
            return activeDebugLaunchForOutput(outputRoot);
        }
        default List<String> activeDebugLaunchesForOutput(Path outputRoot) {
            String launchId = activeDebugLaunchForOutput(outputRoot);
            return launchId == null ? Collections.<String>emptyList()
                    : Collections.singletonList(launchId);
        }
        default List<String> activeDebugLaunchesForClass(Path outputRoot, String relativePath) {
            String launchId = activeDebugLaunchForClass(outputRoot, relativePath);
            return launchId == null ? Collections.<String>emptyList()
                    : Collections.singletonList(launchId);
        }
        void recordWarning(String event, String reason);
        void reloadClasses(String launchId, List<ClassUpdate> updates);
    }

    private final SessionOperations sessions;
    private final Map<Thread, BatchState> collecting = new IdentityHashMap<Thread, BatchState>();
    private long nextBatchId;

    public ClassCompilationListener(HotReloadProjectService sessions) {
        if (sessions == null) throw new NullPointerException("sessions");
        this.sessions = new SessionOperations() {
            @Override public boolean isJavaReloadEnabled() {
                return sessions.isJavaReloadEnabled();
            }

            @Override public String activeDebugLaunchForOutput(Path outputRoot) {
                return sessions.activeDebugLaunchForOutput(outputRoot);
            }

            @Override public String activeDebugLaunchForClass(Path outputRoot, String relativePath) {
                return sessions.activeDebugLaunchForClass(outputRoot, relativePath);
            }

            @Override public List<String> activeDebugLaunchesForOutput(Path outputRoot) {
                return sessions.activeDebugLaunchesForOutput(outputRoot);
            }

            @Override public List<String> activeDebugLaunchesForClass(Path outputRoot, String relativePath) {
                return sessions.activeDebugLaunchesForClass(outputRoot, relativePath);
            }

            @Override public void recordWarning(String event, String reason) {
                sessions.recordWarning(event, reason);
            }

            @Override public void reloadClasses(String launchId, List<ClassUpdate> updates) {
                sessions.reloadClasses(launchId, updates);
            }
        };
    }

    ClassCompilationListener(SessionOperations sessions) {
        if (sessions == null) throw new NullPointerException("sessions");
        this.sessions = sessions;
    }

    @Override public void fileGenerated(String outputRoot, String relativePath) {
        if (!sessions.isJavaReloadEnabled()) return;
        Path root;
        List<String> launchIds;
        try {
            root = toPath(outputRoot);
            launchIds = sessions.activeDebugLaunchesForClass(root, relativePath);
        } catch (RuntimeException failure) {
            sessions.recordWarning("CLASS_OUTPUT_REJECTED", failure.getClass().getSimpleName());
            return;
        }
        if (launchIds == null || launchIds.isEmpty()) return;

        String failureReason = null;
        boolean batchLimitReached = false;
        synchronized (this) {
            Thread callbackThread = Thread.currentThread();
            BatchState state = collecting.get(callbackThread);
            if (state == null) {
                if (collecting.size() >= MAX_ACTIVE_BATCHES) {
                    batchLimitReached = true;
                } else {
                    state = new BatchState(++nextBatchId);
                    collecting.put(callbackThread, state);
                }
            }
            if (state != null) {
                try {
                    for (String launchId : new LinkedHashSet<String>(launchIds)) {
                        if (launchId == null || launchId.isEmpty()) continue;
                        ClassFileBatchCollector collector = state.collectorFor(launchId);
                        if (collector == null) {
                            state.routingRejected = true;
                            failureReason = "concurrent_compile_launch_limit";
                            break;
                        } else {
                            collector.record(root, relativePath);
                        }
                    }
                } catch (RuntimeException failure) {
                    state.routingRejected = true;
                    failureReason = failure.getClass().getSimpleName();
                }
            }
        }
        if (batchLimitReached) {
            sessions.recordWarning("CLASS_OUTPUT_REJECTED", "concurrent_compile_batch_limit");
            return;
        }
        if (failureReason != null) sessions.recordWarning("CLASS_OUTPUT_REJECTED", failureReason);
    }

    @Override public void compilationFinished(boolean aborted, int errors, int warnings,
                                              CompileContext compileContext) {
        finish(compileContext, !aborted && errors == 0);
    }

    @Override public void automakeCompilationFinished(int errors, int warnings,
                                                      CompileContext compileContext) {
        finish(compileContext, errors == 0);
    }

    private void finish(CompileContext compileContext, boolean successful) {
        BatchState state;
        boolean ambiguous = false;
        synchronized (this) {
            state = collecting.remove(Thread.currentThread());
            if (state == null && collecting.size() == 1) {
                Iterator<BatchState> states = collecting.values().iterator();
                state = states.next();
                states.remove();
            } else if (state == null && !collecting.isEmpty()) {
                collecting.clear();
                ambiguous = true;
            }
        }
        if (!sessions.isJavaReloadEnabled()) return;
        if (ambiguous) {
            sessions.recordWarning("CLASS_BATCH_SKIPPED", "ambiguous_compile_finish");
            return;
        }
        if (state == null) return;
        if (state.routingRejected) {
            sessions.recordWarning("CLASS_BATCH_SKIPPED",
                    "concurrent_compile_callbacks_batch_" + state.batchId);
            return;
        }
        for (Map.Entry<String, ClassFileBatchCollector> entry : state.collectors.entrySet()) {
            try {
                List<ClassUpdate> updates = entry.getValue().finish(successful);
                if (!updates.isEmpty()) {
                    sessions.reloadClasses(entry.getKey(), updates);
                }
            } catch (Exception failure) {
                sessions.recordWarning("CLASS_BATCH_SKIPPED",
                        failure.getClass().getSimpleName() + "_batch_" + state.batchId);
            }
        }
    }

    static Path toPath(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("outputRoot is required");
        int colon = value.indexOf(':');
        if (colon > 1 && value.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            final URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("outputRoot URI is malformed", e);
            }
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || (uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPath() == null || uri.getPath().isEmpty()) {
                throw new IllegalArgumentException("outputRoot must be a local file URI");
            }
            try {
                URI lowerCaseScheme = URI.create("file" + value.substring(colon));
                return Paths.get(lowerCaseScheme);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("outputRoot URI is not a local path", e);
            }
        }
        return Paths.get(value);
    }

    private static final class BatchState {
        private final long batchId;
        private final Map<String, ClassFileBatchCollector> collectors =
                new LinkedHashMap<String, ClassFileBatchCollector>();
        private boolean routingRejected;

        private BatchState(long batchId) {
            this.batchId = batchId;
        }

        private ClassFileBatchCollector collectorFor(String launchId) {
            ClassFileBatchCollector collector = collectors.get(launchId);
            if (collector != null) return collector;
            if (collectors.size() >= MAX_LAUNCHES_PER_BATCH) return null;
            collector = new ClassFileBatchCollector(ProtocolLimits.MAX_CLASS_BATCH,
                    ProtocolLimits.MAX_ITEM_BYTES, ProtocolLimits.MAX_FRAME_BYTES);
            collectors.put(launchId, collector);
            return collector;
        }
    }
}
