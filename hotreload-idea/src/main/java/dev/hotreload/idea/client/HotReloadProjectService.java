package dev.hotreload.idea.client;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.BaseProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.compiler.CompilerTopics;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.MessageBusConnection;
import dev.hotreload.idea.change.ClassCompilationListener;
import dev.hotreload.idea.change.DebugClasspathMatcher;
import dev.hotreload.idea.change.MapperReloadQueue;
import dev.hotreload.idea.change.MapperUpdateReader;
import dev.hotreload.idea.change.ConfigFileChangeListener;
import dev.hotreload.idea.change.ConfigUpdateReader;
import dev.hotreload.idea.change.JavaSourceLifecycleListener;
import dev.hotreload.idea.change.MapperXmlChangeListener;
import dev.hotreload.idea.change.StaticResourceChangeListener;
import dev.hotreload.idea.change.StaticResourceSynchronizer;
import dev.hotreload.idea.settings.HotReloadSettings;
import dev.hotreload.idea.settings.HotReloadSettingsResolver;
import dev.hotreload.idea.change.PathSafety;
import dev.hotreload.idea.logging.HotReloadLogBuffer;
import dev.hotreload.idea.logging.PluginSessionDiagnostics;
import dev.hotreload.idea.run.AgentLaunchSpec;
import dev.hotreload.idea.run.DebugExecutionListener;
import dev.hotreload.idea.run.IdeaBuiltinHotSwapGuard;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.HelloResponse;
import dev.hotreload.protocol.message.MapperUpdate;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadItemResult;
import dev.hotreload.protocol.message.ReloadResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HotReloadProjectService implements Disposable {
    private static final int MAX_PROJECT_SESSIONS = 16;
    private static final int MAX_PENDING_XML = 256;
    private static final long XML_DEBOUNCE_MILLIS = 250L;
    private static final long SESSION_POLL_MILLIS = 100L;
    private static final long SESSION_CONNECT_TIMEOUT_MILLIS = 28_000L;
    private static final long SCHEDULED_EXECUTION_MAX_AGE_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(5L);
    private static final int MAX_SCHEDULED_EXECUTIONS = 64;
    private static final int MAX_PENDING_RESOURCE_TASKS = 256;
    // A directory rename is admitted as one ordered batch, but each resource still consumes a unit.
    private static final int MAX_PENDING_STATIC_RESOURCE_OPERATIONS = 8_192;
    private static final int STATIC_COMMITTED_RETRY_LIMIT = 20;
    private static final long STATIC_COMMITTED_RETRY_MILLIS = 50L;

    private final ScheduledExecutorService scheduler = AppExecutorUtil.getAppScheduledExecutorService();
    private final BoundedResourceExecutor configResourceExecutor = new BoundedResourceExecutor(
            AppExecutorUtil.createBoundedApplicationPoolExecutor(
                    "HotReload configuration resources", 1), MAX_PENDING_RESOURCE_TASKS);
    private final BoundedResourceExecutor mapperResourceExecutor = new BoundedResourceExecutor(
            AppExecutorUtil.createBoundedApplicationPoolExecutor(
                    "HotReload mapper resources", 1), MAX_PENDING_RESOURCE_TASKS);
    private final BoundedResourceExecutor staticResourceExecutor = new BoundedResourceExecutor(
            AppExecutorUtil.createBoundedApplicationPoolExecutor(
                    "HotReload static resources", 1), MAX_PENDING_STATIC_RESOURCE_OPERATIONS);
    private final PluginSessionDiagnostics diagnostics;
    private final RunningSessionRegistry activeSessions = new RunningSessionRegistry(MAX_PROJECT_SESSIONS);
    private final Map<String, LaunchState> launches = new HashMap<String, LaunchState>();
    private final Map<Long, String> executionLaunches = new HashMap<Long, String>();
    private final Map<Long, ScheduledExecution> scheduledExecutions = new HashMap<Long, ScheduledExecution>();
    private final Map<RunProfile, ArrayDeque<LaunchState>> pendingByProfile =
            new IdentityHashMap<RunProfile, ArrayDeque<LaunchState>>();
    private final MapperReloadQueue mapperQueue;
    private final Project project;
    private final AtomicBoolean disposed = new AtomicBoolean();

    public HotReloadProjectService(Project project) {
        this.project = project;
        HotReloadLogBuffer logBuffer = project.getService(HotReloadLogBuffer.class);
        diagnostics = new PluginSessionDiagnostics(project.getLocationHash(),
                logBuffer == null ? new HotReloadLogBuffer() : logBuffer);
        mapperQueue = new MapperReloadQueue(scheduler, XML_DEBOUNCE_MILLIS, MAX_PENDING_XML,
                (launchId, sourceRoot, outputRoot, file) -> executeMapperResourceTask(launchId,
                        () -> readAndReloadMapper(launchId, sourceRoot, outputRoot, file)));
        MessageBusConnection connection = project.getMessageBus().connect(this);
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new DebugExecutionListener(this));
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new MapperXmlChangeListener(project, this));
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new ConfigFileChangeListener(project, this));
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new StaticResourceChangeListener(project, this));
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new JavaSourceLifecycleListener(project, this));
        connection.subscribe(CompilerTopics.COMPILATION_STATUS, new ClassCompilationListener(this));
        // 上次 IDE 崩溃可能留下未恢复的内置 HotSwap 覆盖；无活跃会话时启动即还原。
        IdeaBuiltinHotSwapGuard.recoverStaleOverride(diagnostics);
        diagnostics.info("PROJECT_SERVICE_START", null, "activeSessions", "0", "pendingSessions", "0");
    }

    public AgentLaunchSpec prepareLaunch(RunProfile profile, Path launchRoot, Path agentJar,
                                         List<String> classpathEntries) throws IOException {
        if (profile == null) throw new NullPointerException("profile");
        AgentLaunchSpec spec;
        LaunchState state;
        synchronized (this) {
            if (disposed.get()) throw new IllegalStateException("project service is disposed");
            if (launches.size() >= MAX_PROJECT_SESSIONS) throw new IOException("Too many Debug sessions");
            boolean verboseLogs = false;
            try { verboseLogs = HotReloadSettings.getInstance().isShowVerboseLogs(); } catch (Throwable ignored) { }
            spec = AgentLaunchSpec.create(launchRoot, agentJar, verboseLogs);
            SessionConnector connector = new SessionConnector(spec, scheduler,
                    (event, launchId) -> diagnostics.info(event, launchId),
                    SESSION_POLL_MILLIS, SESSION_CONNECT_TIMEOUT_MILLIS);
            state = new LaunchState(profile, spec, connector, normalizeClasspath(classpathEntries));
            launches.put(spec.getLaunchId(), state);
            pendingByProfile.computeIfAbsent(profile, ignored -> new ArrayDeque<LaunchState>()).addLast(state);
            associateScheduledExecution(state);
        }
        try {
            LaunchState captured = state;
            state.connector.start(new SessionConnector.Listener() {
                @Override public void connected(HotReloadClient client, HelloResponse response) {
                    authenticated(captured, client, response);
                }

                @Override public void deadlineExpired() {
                    closeLaunch(captured.spec.getLaunchId(), false);
                }
            });
            diagnostics.info("SESSION_PENDING", spec.getLaunchId(), "pendingSessions",
                    Integer.toString(pendingCount()));
            return spec;
        } catch (RuntimeException failure) {
            closeLaunch(spec.getLaunchId(), true);
            throw failure;
        }
    }

    public void bindProcess(RunProfile profile, ProcessHandler handler) {
        if (profile == null || handler == null) return;
        String commandLaunchId = launchIdFromProcess(handler);
        if (commandLaunchId != null) {
            bindProcess(commandLaunchId, handler);
            return;
        }
        LaunchState state;
        synchronized (this) {
            ArrayDeque<LaunchState> pending = pendingByProfile.get(profile);
            if (pending == null) return;
            state = onlyPending(pending);
            if (state == null) {
                diagnostics.warn("PROCESS_BIND_AMBIGUOUS", null,
                        "pendingSessions", Integer.toString(pending.size()));
                return;
            }
        }
        bindProcess(state.spec.getLaunchId(), handler);
    }

    public void bindProcess(long executionId, RunProfile profile, ProcessHandler handler) {
        String commandLaunchId = launchIdFromProcess(handler);
        String launchId;
        synchronized (this) {
            purgeScheduledExecutions(System.nanoTime());
            launchId = commandLaunchId;
            if (launchId == null) launchId = executionLaunches.get(executionId);
            if (launchId == null) launchId = onlyPendingLaunchId(profile);
            LaunchState state = launchId == null ? null : launches.get(launchId);
            if (state != null && !state.closed.get()) {
                executionLaunches.put(executionId, launchId);
            } else {
                executionLaunches.remove(executionId);
                launchId = null;
            }
            scheduledExecutions.remove(executionId);
        }
        if (launchId == null) {
            if (profile != null) diagnostics.warn("PROCESS_BIND_UNIDENTIFIED", null);
            return;
        }
        bindProcess(launchId, handler);
    }

    public void processStartScheduled(long executionId, RunProfile profile) {
        if (profile == null) return;
        synchronized (this) {
            long now = System.nanoTime();
            purgeScheduledExecutions(now);
            if (scheduledExecutions.size() >= MAX_SCHEDULED_EXECUTIONS) {
                removeOldestScheduledExecution();
            }
            scheduledExecutions.put(executionId, new ScheduledExecution(profile, now));
        }
    }

    /** Binds a process only to the explicitly identified launch. */
    public void bindProcess(String launchId, ProcessHandler handler) {
        if (launchId == null || handler == null) return;
        LaunchState state;
        synchronized (this) {
            state = launches.get(launchId);
            if (state == null || state.closed.get()) return;
            if (state.processHandler == handler) return;
            if (state.processHandler != null) {
                diagnostics.warn("PROCESS_BIND_CONFLICT", launchId);
                return;
            }
            removePending(state);
            state.processHandler = handler;
            ProcessListener listener = new ProcessAdapter() {
                @Override public void processTerminated(ProcessEvent event) {
                    closeLaunch(launchId, true);
                }
            };
            state.processListener = listener;
            handler.addProcessListener(listener);
        }
        diagnostics.info("PROCESS_BOUND", state.spec.getLaunchId());
        if (handler.isProcessTerminated()) closeLaunch(state.spec.getLaunchId(), true);
    }

    public void processNotStarted(RunProfile profile) {
        // Execution callbacks do not carry a launch identity on all supported IDEA versions.
        // Never close every launch for a shared RunProfile; the connector deadline cleans up
        // an unidentifiable launch without risking a concurrent session.
        if (profile != null) diagnostics.warn("PROCESS_NOT_STARTED_UNIDENTIFIED", null);
    }

    public void processNotStarted(String launchId) {
        if (launchId != null) closeLaunch(launchId, true);
    }

    public void processNotStarted(long executionId, RunProfile profile) {
        String launchId;
        synchronized (this) {
            launchId = executionLaunches.remove(executionId);
            scheduledExecutions.remove(executionId);
        }
        if (launchId != null) closeLaunch(launchId, true);
        else processNotStarted(profile);
    }

    public void processTerminated(ProcessHandler handler) {
        String launchId = null;
        synchronized (this) {
            for (LaunchState state : launches.values()) {
                if (state.processHandler == handler) {
                    launchId = state.spec.getLaunchId();
                    break;
                }
            }
        }
        if (launchId != null) closeLaunch(launchId, true);
    }

    
    public boolean isJavaReloadEnabled() {
        return isFeatureEnabled(HotReloadSettingsResolver.Feature.JAVA);
    }

    public boolean isMapperReloadEnabled() {
        return isFeatureEnabled(HotReloadSettingsResolver.Feature.MAPPER);
    }

    public boolean isConfigReloadEnabled() {
        return isFeatureEnabled(HotReloadSettingsResolver.Feature.CONFIG);
    }

    public boolean isStaticResourceReloadEnabled() {
        return isFeatureEnabled(HotReloadSettingsResolver.Feature.STATIC_RESOURCE);
    }

    public void settingsChanged() {
        updateHotSwapGuards(isJavaReloadEnabled());
    }

    private boolean isFeatureEnabled(HotReloadSettingsResolver.Feature feature) {
        if (disposed.get() || project.isDisposed()) return false;
        try {
            return HotReloadSettingsResolver.resolve(project).isFeatureEnabled(feature);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void scheduleConfigReload(Path sourceRoot, Path outputRoot, Path file) {
        if (!isConfigReloadEnabled()) return;
        List<String> launchIds = activeDebugLaunchesForOutput(outputRoot);
        if (launchIds.isEmpty()) {
            recordWarning("CONFIG_RELOAD_SKIPPED", "debug_session_not_bound_at_save");
            return;
        }
        executeConfigResourceTask(() -> readAndReloadConfig(
                launchIds, sourceRoot, outputRoot, file));
    }

    private void executeConfigResourceTask(Runnable task) {
        if (disposed.get()) return;
        if (!configResourceExecutor.execute(task)) {
            recordWarning("CONFIG_RELOAD_SKIPPED", "queue_full_or_closed");
        }
    }

    private void readAndReloadConfig(List<String> expectedLaunchIds, Path sourceRoot,
                                     Path outputRoot, Path file) {
        if (!isConfigReloadEnabled()) return;
        ConfigUpdateReader.Result update;
        try {
            update = ConfigUpdateReader.read(sourceRoot, file);
        } catch (Exception failure) {
            diagnostics.warn("CONFIG_READ_FAILED", null, "reason", failure.getClass().getSimpleName(),
                    "source", String.valueOf(file));
            return;
        }
        String resourcePath = update.getResourceId();
        byte[] content = update.getContent();
        String type = resourcePath.toLowerCase(Locale.ROOT).endsWith(".properties")
                ? "properties" : "yaml";
        for (String expectedLaunchId : expectedLaunchIds) {
            RunningSessionRegistry.Session session = activeSessions.get(expectedLaunchId);
            if (session == null || !expectedLaunchId.equals(session.getLaunchId())) {
                diagnostics.warn("CONFIG_RELOAD_SKIPPED", session == null ? null : session.getLaunchId(),
                        "reason", session == null ? missingSessionReason(expectedLaunchId)
                                : "debug_session_changed");
                continue;
            }
            LaunchState launch = launch(expectedLaunchId);
            if (launch == null) {
                diagnostics.warn("CONFIG_RELOAD_SKIPPED", expectedLaunchId,
                        "reason", "launch_state_missing", "resourceId", resourcePath);
                continue;
            }
            DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateLoadedResource(
                    outputRoot, resourcePath, launch.classpathEntries);
            if (!decision.isAccepted()) {
                diagnostics.warn("CONFIG_RELOAD_SKIPPED", expectedLaunchId,
                        "reason", decision.reason(), "resourceId", resourcePath,
                        "detail", decision.summary());
                continue;
            }
            if (!isConfigReloadEnabled()) return;
            diagnostics.info("CONFIG_RELOAD_SEND", session.getLaunchId(),
                    "resourceId", resourcePath, "contentType", type,
                    "payloadBytes", Integer.toString(content.length));
            CompletableFuture<ReloadResponse> future =
                    session.getClient().reloadResource(resourcePath, content, type);
            observe(session, "CONFIG_RELOAD_RESULT", future);
            awaitObserved(future);
        }
    }

    public void scheduleStaticResourceReload(Path sourceRoot, Path outputRoot, Path file) {
        if (!isStaticResourceReloadEnabled()) return;
        List<String> launchIds = activeDebugLaunchesForOutput(outputRoot);
        if (launchIds.isEmpty()) {
            recordWarning("STATIC_RELOAD_SKIPPED", "debug_session_not_bound_at_save");
            return;
        }
        String primaryLaunchId = launchIds.get(0);
        executeStaticResourceTask(() -> {
            List<StaticResourceNotification> notifications = new ArrayList<StaticResourceNotification>(1);
            synchronizeAndReloadStaticResource(primaryLaunchId, sourceRoot, outputRoot, file,
                    false, notifications, launchIds);
            notifyStaticResourceChanges(notifications);
        });
    }

    public void scheduleStaticResourceRemoval(Path outputRoot, String resourceId) {
        if (!isStaticResourceReloadEnabled()) return;
        List<String> launchIds = activeDebugLaunchesForOutput(outputRoot);
        if (launchIds.isEmpty()) {
            recordWarning("STATIC_RELOAD_SKIPPED", "debug_session_not_bound_at_save");
            return;
        }
        String primaryLaunchId = launchIds.get(0);
        executeStaticResourceTask(() -> {
            List<StaticResourceNotification> notifications = new ArrayList<StaticResourceNotification>(1);
            removeAndReloadStaticResource(primaryLaunchId, outputRoot, resourceId,
                    false, notifications, launchIds);
            notifyStaticResourceChanges(notifications);
        });
    }

    public void scheduleStaticResourceChanges(
            List<StaticResourceChangeListener.ResourceLocation> removals,
            List<StaticResourceChangeListener.ResourceLocation> synchronizations) {
        scheduleStaticResourceChanges(removals, synchronizations, false);
    }

    public void scheduleCommittedStaticResourceChanges(
            List<StaticResourceChangeListener.ResourceLocation> removals,
            List<StaticResourceChangeListener.ResourceLocation> synchronizations) {
        scheduleStaticResourceChanges(removals, synchronizations, true);
    }

    private void scheduleStaticResourceChanges(
            List<StaticResourceChangeListener.ResourceLocation> removals,
            List<StaticResourceChangeListener.ResourceLocation> synchronizations,
            boolean committedLifecycle) {
        if (!committedLifecycle && !isStaticResourceReloadEnabled()) return;
        List<BoundStaticResourceChange> changes = new ArrayList<BoundStaticResourceChange>();
        bindStaticResourceChanges(changes, removals, true, committedLifecycle);
        bindStaticResourceChanges(changes, synchronizations, false, committedLifecycle);
        if (changes.isEmpty()) return;
        executeStaticResourceTask(() -> {
            if (!committedLifecycle && !isStaticResourceReloadEnabled()) return;
            List<StaticResourceNotification> notifications =
                    new ArrayList<StaticResourceNotification>(changes.size());
            for (BoundStaticResourceChange change : changes) {
                StaticResourceChangeListener.ResourceLocation location = change.location;
                try {
                    if (change.removal) {
                        removeAndReloadStaticResource(change.primaryLaunchId(),
                                location.getOutputRoot(), location.getResourceId(), committedLifecycle,
                                notifications, change.launchIds);
                    } else {
                        synchronizeAndReloadStaticResource(change.primaryLaunchId(),
                                location.getSourceRoot(), location.getOutputRoot(),
                                location.getSourceFile(), committedLifecycle, notifications, change.launchIds);
                    }
                } catch (RuntimeException failure) {
                    logStaticSyncFailure(change.primaryLaunchId(), failure);
                }
            }
            notifyStaticResourceChanges(notifications);
        }, changes.size(), committedLifecycle);
    }

    private void bindStaticResourceChanges(List<BoundStaticResourceChange> target,
            List<StaticResourceChangeListener.ResourceLocation> locations,
            boolean removal, boolean committedLifecycle) {
        if (locations == null) return;
        for (StaticResourceChangeListener.ResourceLocation location : locations) {
            if (location == null) continue;
            List<String> launchIds = activeDebugLaunchesForOutput(location.getOutputRoot());
            if (launchIds.isEmpty() && !committedLifecycle) {
                recordWarning("STATIC_RELOAD_SKIPPED", "debug_session_not_bound_at_save");
                continue;
            }
            target.add(new BoundStaticResourceChange(launchIds, location, removal));
        }
    }

    private void executeStaticResourceTask(Runnable task) {
        executeStaticResourceTask(task, 1);
    }

    private void executeStaticResourceTask(Runnable task, int workUnits) {
        executeStaticResourceTask(task, workUnits, false);
    }

    private void executeStaticResourceTask(Runnable task, int workUnits, boolean committedLifecycle) {
        if (disposed.get()) return;
        if (staticResourceExecutor.execute(task, workUnits)) return;
        if (committedLifecycle) {
            retryCommittedStaticResourceTask(task, workUnits, 1);
        } else {
            recordWarning("STATIC_RELOAD_SKIPPED", "queue_full_or_closed");
        }
    }

    private void retryCommittedStaticResourceTask(Runnable task, int workUnits, int attempt) {
        if (disposed.get()) return;
        if (workUnits > MAX_PENDING_STATIC_RESOURCE_OPERATIONS) {
            recordWarning("STATIC_RESTART_REQUIRED", "batch_exceeds_queue_capacity");
            return;
        }
        if (staticResourceExecutor.execute(task, workUnits)) return;
        if (attempt >= STATIC_COMMITTED_RETRY_LIMIT) {
            recordWarning("STATIC_RESTART_REQUIRED", "queue_busy_after_retries");
            return;
        }
        try {
            scheduler.schedule(() -> retryCommittedStaticResourceTask(task, workUnits, attempt + 1),
                    STATIC_COMMITTED_RETRY_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            recordWarning("STATIC_RESTART_REQUIRED", "queue_full_or_closed");
        }
    }

    private void synchronizeAndReloadStaticResource(String expectedLaunchId, Path sourceRoot,
                                                    Path outputRoot, Path file) {
        synchronizeAndReloadStaticResource(expectedLaunchId, sourceRoot, outputRoot, file,
                false, null, Collections.singletonList(expectedLaunchId));
    }

    private void synchronizeAndReloadStaticResource(String expectedLaunchId, Path sourceRoot,
                                                     Path outputRoot, Path file,
                                                     boolean committedBatch,
                                                     List<StaticResourceNotification> notifications,
                                                     List<String> notificationLaunchIds) {
        if (!committedBatch && !isStaticResourceReloadEnabled()) return;
        final String resourceId;
        try {
            resourceId = StaticResourceSynchronizer.resourceId(sourceRoot, file);
        } catch (Exception failure) {
            logStaticSyncFailure(expectedLaunchId, failure);
            return;
        }

        if (!committedBatch) {
            List<String> validLaunchIds = validStaticLaunches(outputRoot, resourceId,
                    notificationLaunchIds);
            if (validLaunchIds.isEmpty()) {
                diagnostics.warn("STATIC_RELOAD_SKIPPED", expectedLaunchId,
                        "reason", "resource_not_loaded_or_debug_session_changed");
                return;
            }
            expectedLaunchId = validLaunchIds.get(0);
            notificationLaunchIds = validLaunchIds;
            if (!isStaticResourceReloadEnabled()) return;
        }

        // Once a rename/move batch starts, finish its output-directory lifecycle even if the
        // setting or Debug session changes. Only the later Agent notification remains optional.
        StaticResourceSynchronizer.Result synchronizedResource;
        try {
            synchronizedResource = StaticResourceSynchronizer.synchronize(
                    sourceRoot, outputRoot, file);
        } catch (Exception failure) {
            logStaticSyncFailure(expectedLaunchId, failure);
            return;
        }

        diagnostics.info("STATIC_RESOURCE_SYNCED", expectedLaunchId,
                "resourceId", synchronizedResource.getResourceId(),
                "target", synchronizedResource.getTarget().toString(),
                "payloadBytes", Integer.toString(synchronizedResource.getContentLength()));

        if (notifications != null) {
            notifications.add(new StaticResourceNotification(notificationLaunchIds, outputRoot,
                    synchronizedResource.getResourceId()));
            return;
        }
        notifyStaticResourceChange(expectedLaunchId, outputRoot,
                synchronizedResource.getResourceId());
    }

    private void removeAndReloadStaticResource(String expectedLaunchId, Path outputRoot,
                                               String resourceId) {
        removeAndReloadStaticResource(expectedLaunchId, outputRoot, resourceId, false, null,
                Collections.singletonList(expectedLaunchId));
    }

    private void removeAndReloadStaticResource(String expectedLaunchId, Path outputRoot,
                                                String resourceId, boolean committedBatch,
                                                List<StaticResourceNotification> notifications,
                                                List<String> notificationLaunchIds) {
        if (!committedBatch && !isStaticResourceReloadEnabled()) return;
        if (!committedBatch) {
            List<String> validLaunchIds = validStaticLaunches(outputRoot, resourceId,
                    notificationLaunchIds);
            if (validLaunchIds.isEmpty()) {
                diagnostics.warn("STATIC_RELOAD_SKIPPED", expectedLaunchId,
                        "reason", "resource_not_loaded_or_debug_session_changed");
                return;
            }
            expectedLaunchId = validLaunchIds.get(0);
            notificationLaunchIds = validLaunchIds;
            if (!isStaticResourceReloadEnabled()) return;
        }

        StaticResourceSynchronizer.RemovalResult removal;
        try {
            removal = StaticResourceSynchronizer.remove(outputRoot, resourceId);
        } catch (Exception failure) {
            logStaticSyncFailure(expectedLaunchId, failure);
            return;
        }
        diagnostics.info("STATIC_RESOURCE_REMOVED", expectedLaunchId,
                "resourceId", removal.getResourceId(), "target", removal.getTarget().toString(),
                "removed", Boolean.toString(removal.wasRemoved()));

        if (notifications != null) {
            notifications.add(new StaticResourceNotification(notificationLaunchIds, outputRoot,
                    removal.getResourceId()));
            return;
        }
        notifyStaticResourceChange(expectedLaunchId, outputRoot, removal.getResourceId());
    }

    /** Re-check ownership at execution time because a Debug session may end during debounce. */
    private List<String> validStaticLaunches(Path outputRoot, String resourceId,
                                              List<String> preferredLaunchIds) {
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        if (preferredLaunchIds != null) candidates.addAll(preferredLaunchIds);
        candidates.addAll(activeDebugLaunchesForOutput(outputRoot));
        List<String> valid = new ArrayList<String>();
        for (String launchId : candidates) {
            if (launchId == null || launchId.isEmpty()) continue;
            RunningSessionRegistry.Session session = activeSessions.get(launchId);
            LaunchState launch = launch(launchId);
            if (session == null || launch == null || !launchId.equals(session.getLaunchId())) continue;
            DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                    outputRoot, resourceId, launch.classpathEntries);
            if (decision.isAccepted()
                    || decision.getCode() == DebugClasspathMatcher.DecisionCode.RESOURCE_SHADOWED) {
                valid.add(launchId);
            }
        }
        return valid;
    }

    private void notifyStaticResourceChanges(List<StaticResourceNotification> notifications) {
        for (StaticResourceNotification notification : notifications) {
            for (String launchId : notification.launchIds) {
                notifyStaticResourceChange(launchId, notification.outputRoot,
                        notification.resourceId);
            }
        }
    }

    private void notifyStaticResourceChange(String expectedLaunchId, Path outputRoot,
                                            String resourceId) {
        if (!isStaticResourceReloadEnabled()) return;
        RunningSessionRegistry.Session session = activeSessions.get(expectedLaunchId);
        LaunchState launch = launch(expectedLaunchId);
        if (session == null || !expectedLaunchId.equals(session.getLaunchId())) {
            diagnostics.warn("STATIC_RELOAD_SKIPPED", session == null ? null : session.getLaunchId(),
                    "reason", session == null ? missingSessionReason(expectedLaunchId)
                            : "debug_session_changed");
            return;
        }
        if (launch == null) {
            diagnostics.warn("STATIC_RELOAD_SKIPPED", expectedLaunchId,
                    "reason", "launch_state_missing");
            return;
        }
        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                outputRoot, resourceId, launch.classpathEntries);
        if (!decision.isAccepted()) {
            diagnostics.warn("STATIC_RELOAD_SKIPPED", expectedLaunchId,
                    "reason", decision.reason(), "detail", decision.summary());
            return;
        }
        // The target JVM reads the installed classpath file; the request only invalidates caches.
        sendStaticResourceReload(session, resourceId, new byte[0],
                detectContentType(resourceId));
    }

    private void sendStaticResourceReload(RunningSessionRegistry.Session session,
                                          String resourcePath, byte[] content, String type) {
        if (!isStaticResourceReloadEnabled()) return;
        diagnostics.info("STATIC_RELOAD_SEND", session.getLaunchId(),
                "resourceId", resourcePath, "contentType", type,
                "payloadBytes", Integer.toString(content.length));
        CompletableFuture<ReloadResponse> future =
                session.getClient().reloadResource(resourcePath, content, type);
        observe(session, "STATIC_RELOAD_RESULT", future);
        awaitObserved(future);
    }

    private static void awaitObserved(CompletableFuture<ReloadResponse> future) {
        try {
            future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // observe() records every failure; only transport failures close the affected launch.
        }
    }

    private void logStaticSyncFailure(String launchId, Exception failure) {
        diagnostics.warn("STATIC_SYNC_FAILED", launchId,
                "reason", failure instanceof IllegalArgumentException
                        ? "bad_input" : "resource_install_failed",
                "detail", failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage());
    }

    private static String detectContentType(String resourcePath) {
        if (resourcePath == null) return "static";
        String lower = resourcePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".ico") ||
            lower.endsWith(".webp")) {
            return "image";
        }
        if (lower.endsWith(".woff") || lower.endsWith(".woff2") ||
            lower.endsWith(".ttf") || lower.endsWith(".eot") || lower.endsWith(".otf")) {
            return "font";
        }
        return "static";
    }

    public void scheduleMapperReload(Path sourceRoot, Path outputRoot, Path file) {
        if (!isMapperReloadEnabled()) return;
        List<String> launchIds = activeDebugLaunchesForOutput(outputRoot);
        if (launchIds.isEmpty()) {
            recordWarning("XML_RELOAD_SKIPPED", "debug_session_not_bound_at_save");
            return;
        }
        if (!mapperQueue.scheduleAll(launchIds, sourceRoot, outputRoot, file)) {
            recordWarning("XML_RESTART_REQUIRED", "queue_full_or_closed");
        }
    }

    private void executeMapperResourceTask(String launchId, Runnable task) {
        if (disposed.get() || !isMapperReloadEnabled()) return;
        if (!mapperResourceExecutor.execute(task)) {
            diagnostics.warn("XML_RESTART_REQUIRED", launchId,
                    "reason", "queue_full_or_closed");
        }
    }

    public void reloadClasses(List<ClassUpdate> updates) {
        reloadClasses(null, updates);
    }

    public void reloadClasses(String expectedLaunchId, List<ClassUpdate> updates) {
        if (!isJavaReloadEnabled() || updates == null || updates.isEmpty()) return;
        RunningSessionRegistry.Session session = expectedLaunchId == null
                ? activeSessions.only() : activeSessions.get(expectedLaunchId);
        if (session == null || (expectedLaunchId != null
                && !expectedLaunchId.equals(session.getLaunchId()))) {
            diagnostics.warn("CLASS_BATCH_SKIPPED", null, "reason",
                    session == null ? missingSessionReason(expectedLaunchId)
                            : "debug_session_changed",
                    "activeSessions", Integer.toString(activeSessions.size()));
            return;
        }
        if (!isJavaReloadEnabled()) return;
        diagnostics.info("CLASS_BATCH_SEND", session.getLaunchId(), "classCount",
                Integer.toString(updates.size()));
        observe(session, "CLASS_BATCH_RESULT", session.getClient().reloadClasses(updates));
    }

    public String activeDebugLaunchForOutput(Path outputRoot) {
        List<String> matches = activeDebugLaunchesForOutput(outputRoot);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public List<String> activeDebugLaunchesForOutput(Path outputRoot) {
        List<String> matches = new ArrayList<String>();
        for (RunningSessionRegistry.Session session : activeSessions.snapshot()) {
            LaunchState state = launch(session.getLaunchId());
            if (state == null || !DebugClasspathMatcher.containsOutputRoot(
                    outputRoot, state.classpathEntries)) {
                continue;
            }
            matches.add(session.getLaunchId());
        }
        return Collections.unmodifiableList(matches);
    }

    public String activeDebugLaunchForClass(Path outputRoot, String relativePath) {
        List<String> matches = activeDebugLaunchesForClass(outputRoot, relativePath);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public List<String> activeDebugLaunchesForClass(Path outputRoot, String relativePath) {
        if (outputRoot == null || relativePath == null || relativePath.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> matches = new ArrayList<String>();
        for (RunningSessionRegistry.Session session : activeSessions.snapshot()) {
            LaunchState state = launch(session.getLaunchId());
            if (state == null) continue;
            // fileGenerated may precede the final atomic class-file move. Verify ordered
            // classpath ownership now; ClassFileBatchCollector performs the strict final read.
            DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                    outputRoot, relativePath, state.classpathEntries);
            if (!decision.isAccepted()) continue;
            matches.add(session.getLaunchId());
        }
        return Collections.unmodifiableList(matches);
    }

    public void recordWarning(String event, String reason) {
        diagnostics.warn(event, null, "reason", reason);
    }

    private String missingSessionReason(String expectedLaunchId) {
        if (expectedLaunchId == null || activeSessions.size() == 0) {
            return "active_session_count";
        }
        return "debug_session_changed";
    }

    public void recordInfo(String event, String launchId, String... fields) {
        diagnostics.info(event, launchId, fields);
    }

    private void readAndReloadMapper(String expectedLaunchId, Path sourceRoot, Path outputRoot, Path file) {
        if (!isMapperReloadEnabled()) return;
        MapperUpdate update;
        try {
            update = MapperUpdateReader.read(sourceRoot, file);
        } catch (Exception failure) {
            diagnostics.warn("XML_READ_FAILED", null, "reason", failure.getClass().getSimpleName());
            return;
        }
        RunningSessionRegistry.Session session = activeSessions.get(expectedLaunchId);
        if (session == null || !expectedLaunchId.equals(session.getLaunchId())) {
            diagnostics.warn("XML_RELOAD_SKIPPED", session == null ? null : session.getLaunchId(), "reason",
                    session == null ? missingSessionReason(expectedLaunchId)
                            : "debug_session_changed",
                    "activeSessions", Integer.toString(activeSessions.size()),
                    "expectedLaunchId", expectedLaunchId);
            return;
        }
        if (!isMapperReloadEnabled()) return;
        LaunchState launch = launch(session.getLaunchId());
        if (launch == null) {
            diagnostics.warn("XML_RELOAD_SKIPPED", session.getLaunchId(),
                    "reason", "launch_state_missing",
                    "resourceId", update.getResourceId());
            return;
        }
        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateMapperResource(
                outputRoot, update.getResourceId(), launch.classpathEntries);
        if (!decision.isAccepted()) {
            diagnostics.warn("XML_RELOAD_SKIPPED", session.getLaunchId(),
                    "reason", decision.reason(),
                    "resourceId", update.getResourceId(),
                    "detail", decision.summary(),
                    "outputRoot", String.valueOf(outputRoot),
                    "matchCount", Integer.toString(decision.getMatchCount()));
            return;
        }
        if (decision.getMatchCount() > 1) {
            diagnostics.info("XML_RESOURCE_AMBIGUOUS_PREFERRED", session.getLaunchId(),
                    "resourceId", update.getResourceId(),
                    "matchCount", Integer.toString(decision.getMatchCount()),
                    "preferredRoot", decision.getPreferredRoot(),
                    "detail", "multi_module_same_relative_path_prefer_event_output");
        }
        if (decision.getCode() == DebugClasspathMatcher.DecisionCode.OK_SOURCE_FALLBACK) {
            diagnostics.info("XML_OUTPUT_COPY_MISSING_USE_SOURCE", session.getLaunchId(),
                    "resourceId", update.getResourceId(),
                    "preferredRoot", decision.getPreferredRoot(),
                    "detail", "compiled_copy_missing_use_source_payload");
        }
        if (!isMapperReloadEnabled()) return;
        diagnostics.info("XML_RELOAD_SEND", session.getLaunchId(),
                "resourceId", update.getResourceId(),
                "matchCount", Integer.toString(decision.getMatchCount()),
                "preferredRoot", decision.getPreferredRoot());
        CompletableFuture<ReloadResponse> future = session.getClient().reloadMapper(update);
        observe(session, "XML_RELOAD_RESULT", future);
        awaitObserved(future);
    }

    private void observe(RunningSessionRegistry.Session session, String event,
                         CompletableFuture<ReloadResponse> future) {
        future.whenComplete((response, failure) -> {
            if (failure != null) {
                if (causedByRequestRejection(failure)) {
                    diagnostics.warn(event, session.getLaunchId(), "result", "request_queue_full",
                            "reason", "request_queue_full");
                } else {
                    diagnostics.warn(event, session.getLaunchId(), "result", "transport_failed",
                            "reason", "transport_failed",
                            "detail", failure.getClass().getSimpleName());
                    closeLaunch(session.getLaunchId(), false);
                }
            } else {
                String errorCode = response.getErrorCode() == null ? "none" : response.getErrorCode().name();
                String message = response.getMessage() == null || response.getMessage().isEmpty()
                        ? "none" : response.getMessage();
                String diagnostic = message;
                String itemId = "none";
                int itemCount = response.getItems().size();
                int successCount = 0;
                int skippedCount = 0;
                int failedCount = 0;
                if (!response.getItems().isEmpty()) {
                    itemId = response.getItems().get(0).getItemId();
                    StringBuilder details = new StringBuilder();
                    for (int i = 0; i < response.getItems().size(); i++) {
                        ReloadItemResult item = response.getItems().get(i);
                        if (item.getStatus() == OperationStatus.SUCCESS) {
                            successCount++;
                        } else if (item.getStatus() == OperationStatus.SKIPPED) {
                            skippedCount++;
                        } else {
                            failedCount++;
                        }
                        // Prefer the first item's diagnostic (already includes spring summary).
                        if (i == 0 && item.getDiagnostic() != null && !item.getDiagnostic().isEmpty()) {
                            diagnostic = item.getDiagnostic();
                        }
                        if (i > 0) details.append(';');
                        details.append(item.getStatus().name());
                        if (item.getErrorCode() != null) details.append('/').append(item.getErrorCode().name());
                        if (item.getDiagnostic() != null && !item.getDiagnostic().isEmpty()
                                && !item.getDiagnostic().equals(diagnostic)) {
                            details.append(':').append(item.getDiagnostic());
                        }
                    }
                    // For multi-item batches keep a compact status rollup in message only when useful.
                    if (itemCount > 1 && details.length() > 0 && details.length() < 240) {
                        message = message + ";items=" + details;
                    }
                }
                if (response.getStatus() == OperationStatus.SUCCESS
                        || response.getStatus() == OperationStatus.SKIPPED) {
                    diagnostics.info(event, session.getLaunchId(), "requestId", response.getRequestId(),
                            "status", response.getStatus().name(), "errorCode", errorCode,
                            "itemCount", Integer.toString(itemCount),
                            "successCount", Integer.toString(successCount),
                            "skippedCount", Integer.toString(skippedCount),
                            "failedCount", Integer.toString(failedCount),
                            "itemId", itemId, "message", message, "detail", diagnostic);
                } else {
                    diagnostics.warn(event, session.getLaunchId(), "requestId", response.getRequestId(),
                            "status", response.getStatus().name(), "errorCode", errorCode,
                            "itemCount", Integer.toString(itemCount),
                            "successCount", Integer.toString(successCount),
                            "skippedCount", Integer.toString(skippedCount),
                            "failedCount", Integer.toString(failedCount),
                            "itemId", itemId, "message", message, "detail", diagnostic);
                }
            }
        });
    }

    private static boolean causedByRequestRejection(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof RejectedExecutionException) return true;
            if (current.getCause() == current) break;
        }
        return false;
    }

    private void authenticated(LaunchState state, HotReloadClient client, HelloResponse response) {
        boolean accepted;
        synchronized (this) {
            accepted = !disposed.get() && launches.get(state.spec.getLaunchId()) == state
                    && !state.closed.get() && activeSessions.add(state.spec.getLaunchId(), client);
            if (accepted) {
                state.client = client;
                state.authenticated = true;
            }
        }
        if (!accepted) {
            client.close();
            closeLaunch(state.spec.getLaunchId(), false);
            return;
        }
        diagnostics.info("SESSION_ACTIVE", state.spec.getLaunchId(), "targetJdk", response.getTargetJavaVersion(),
                "classRedefine", Boolean.toString(response.isClassRedefineSupported()),
                "engine", response.isEnhancedRedefineSupported() ? "enhanced" : "standard",
                "configurationCount", Integer.toString(response.getConfigurationCount()),
                "activeSessions", Integer.toString(activeSessions.size()));
        updateHotSwapGuards(isJavaReloadEnabled());
    }

    private void updateHotSwapGuards(boolean javaReloadEnabled) {
        synchronized (this) {
            for (LaunchState state : launches.values()) {
                if (!state.authenticated || state.closed.get()
                        || state.hotSwapGuardActive == javaReloadEnabled) {
                    continue;
                }
                state.hotSwapGuardActive = javaReloadEnabled;
                if (javaReloadEnabled) {
                    IdeaBuiltinHotSwapGuard.onSessionActivated(project, diagnostics,
                            state.spec.getLaunchId());
                } else {
                    IdeaBuiltinHotSwapGuard.onSessionClosed(project, diagnostics,
                            state.spec.getLaunchId());
                }
            }
        }
    }

    private void closeLaunch(String launchId, boolean processEnded) {
        LaunchState state;
        HotReloadClient client;
        int activeBefore;
        synchronized (this) {
            state = launches.remove(launchId);
            if (state == null || !state.closed.compareAndSet(false, true)) return;
            activeBefore = activeSessions.size();
            client = activeSessions.remove(launchId);
            removePending(state);
            executionLaunches.values().removeIf(launchId::equals);
            purgeScheduledExecutions(System.nanoTime());
            if (!hasOtherLaunchForProfile(state.profile, state)) {
                scheduledExecutions.entrySet().removeIf(entry -> entry.getValue().profile == state.profile);
            }
        }
        state.connector.close();
        if (client == null) client = state.client;
        if (client != null) client.close();
        if (state.processHandler != null && state.processListener != null) {
            state.processHandler.removeProcessListener(state.processListener);
        }
        if (processEnded || state.authenticated) {
            try {
                SessionDescriptorFiles.deleteIfOwned(state.spec.getSessionPath(), launchId,
                        state.spec.getToken());
            } catch (IOException failure) {
                diagnostics.warn("SESSION_DESCRIPTOR_DELETE_FAILED", launchId,
                        "reason", failure.getClass().getSimpleName());
            }
        }
        try {
            state.spec.deleteCredentialIfPresent();
        } catch (IOException failure) {
            diagnostics.warn("SESSION_CREDENTIAL_DELETE_FAILED", launchId,
                    "reason", failure.getClass().getSimpleName());
        }
        if (state.hotSwapGuardActive) {
            state.hotSwapGuardActive = false;
            IdeaBuiltinHotSwapGuard.onSessionClosed(project, diagnostics, launchId);
        }
        diagnostics.info("CLEANUP_COMPLETE", launchId,
                "processEnded", Boolean.toString(processEnded),
                "activeBefore", Integer.toString(activeBefore),
                "activeAfter", Integer.toString(activeSessions.size()),
                "pendingAfter", Integer.toString(pendingCount()),
                "clientClosed", Boolean.toString(client == null || client.isClosed()),
                "clientQueue", Integer.toString(client == null ? 0 : client.getQueueSize()));
    }

    private synchronized void removePending(LaunchState state) {
        ArrayDeque<LaunchState> pending = pendingByProfile.get(state.profile);
        if (pending == null) return;
        pending.remove(state);
        if (pending.isEmpty()) pendingByProfile.remove(state.profile);
    }

    private static LaunchState onlyPending(ArrayDeque<LaunchState> pending) {
        LaunchState result = null;
        int count = 0;
        for (LaunchState candidate : pending) {
            if (candidate.closed.get()) continue;
            result = candidate;
            count++;
        }
        return count == 1 ? result : null;
    }

    private synchronized String onlyPendingLaunchId(RunProfile profile) {
        if (profile == null) return null;
        ArrayDeque<LaunchState> pending = pendingByProfile.get(profile);
        LaunchState state = pending == null ? null : onlyPending(pending);
        return state == null ? null : state.spec.getLaunchId();
    }

    private void associateScheduledExecution(LaunchState state) {
        long now = System.nanoTime();
        purgeScheduledExecutions(now);
        long match = Long.MIN_VALUE;
        int matches = 0;
        for (Map.Entry<Long, ScheduledExecution> entry : scheduledExecutions.entrySet()) {
            ScheduledExecution scheduled = entry.getValue();
            if (scheduled.profile == state.profile
                    && withinCorrelationWindow(state.createdAtNanos, scheduled.scheduledAtNanos)) {
                match = entry.getKey();
                matches++;
            }
        }
        if (matches == 1) {
            executionLaunches.put(match, state.spec.getLaunchId());
            scheduledExecutions.remove(match);
        }
    }

    private void purgeScheduledExecutions(long now) {
        scheduledExecutions.entrySet().removeIf(entry ->
                now - entry.getValue().scheduledAtNanos > SCHEDULED_EXECUTION_MAX_AGE_NANOS);
    }

    private void removeOldestScheduledExecution() {
        Long oldestId = null;
        long oldestAt = Long.MAX_VALUE;
        for (Map.Entry<Long, ScheduledExecution> entry : scheduledExecutions.entrySet()) {
            if (entry.getValue().scheduledAtNanos < oldestAt) {
                oldestAt = entry.getValue().scheduledAtNanos;
                oldestId = entry.getKey();
            }
        }
        if (oldestId != null) scheduledExecutions.remove(oldestId);
    }

    private boolean hasOtherLaunchForProfile(RunProfile profile, LaunchState excluded) {
        for (LaunchState candidate : launches.values()) {
            if (candidate != excluded && candidate.profile == profile) return true;
        }
        return false;
    }

    private static boolean withinCorrelationWindow(long first, long second) {
        long difference = first - second;
        if (difference < 0L) difference = -difference;
        return difference <= SCHEDULED_EXECUTION_MAX_AGE_NANOS;
    }

    private synchronized int pendingCount() {
        int count = 0;
        for (ArrayDeque<LaunchState> states : pendingByProfile.values()) count += states.size();
        return count;
    }

    private synchronized LaunchState launch(String launchId) {
        return launches.get(launchId);
    }

    private static String launchIdFromProcess(ProcessHandler handler) {
        if (!(handler instanceof BaseProcessHandler)) return null;
        String commandLine = ((BaseProcessHandler<?>) handler).getCommandLine();
        return launchIdFromCommandLine(commandLine);
    }

    static String launchIdFromCommandLine(String commandLine) {
        if (commandLine == null) return null;
        String candidate = null;
        for (String argument : commandLineArguments(commandLine)) {
            if (!argument.startsWith("-javaagent:") || argument.indexOf("=session=") < 0
                    || argument.indexOf(",token=") < 0) continue;
            Matcher matcher = LAUNCH_ARGUMENT.matcher(argument);
            while (matcher.find()) {
                if (candidate != null) return null;
                candidate = matcher.group(1);
            }
        }
        if (candidate == null) return null;
        try {
            String canonical = UUID.fromString(candidate).toString();
            return canonical.equals(candidate) ? canonical : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final Pattern LAUNCH_ARGUMENT = Pattern.compile(
            "(?:^|,)launch=([0-9a-fA-F-]{36})(?:,|$)");

    private static List<String> commandLineArguments(String commandLine) {
        List<String> arguments = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < commandLine.length(); i++) {
            char value = commandLine.charAt(i);
            if (quote != 0) {
                if (value == quote) quote = 0;
                else current.append(value);
            } else if (value == '\"' || value == '\'') {
                quote = value;
            } else if (Character.isWhitespace(value)) {
                if (current.length() > 0) {
                    arguments.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(value);
            }
        }
        if (current.length() > 0) arguments.add(current.toString());
        return arguments;
    }

    private static List<Path> normalizeClasspath(List<String> classpathEntries) {
        if (classpathEntries == null) throw new NullPointerException("classpathEntries");
        Set<Path> normalized = new LinkedHashSet<Path>();
        for (String entry : classpathEntries) {
            if (entry == null || entry.isEmpty()) continue;
            try {
                Path path = Paths.get(entry);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    normalized.add(PathSafety.realDirectory(path));
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    normalized.add(PathSafety.realFile(path));
                } else if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    normalized.add(PathSafety.schedulingDirectory(path));
                }
            } catch (RuntimeException ignored) {
                // Non-filesystem classpath entries cannot identify a module output resource.
            } catch (IOException ignored) {
                // Symbolic or unstable classpath entries cannot safely identify runtime resources.
            }
        }
        return Collections.unmodifiableList(new ArrayList<Path>(normalized));
    }

    @Override public void dispose() {
        if (!disposed.compareAndSet(false, true)) return;
        mapperQueue.close();
        configResourceExecutor.shutdownNow();
        mapperResourceExecutor.shutdownNow();
        staticResourceExecutor.shutdownNow();
        List<String> launchIds;
        synchronized (this) {
            launchIds = new ArrayList<String>(launches.keySet());
            scheduledExecutions.clear();
        }
        for (String launchId : launchIds) closeLaunch(launchId, false);
        activeSessions.close();
        IdeaBuiltinHotSwapGuard.forceRestore(project, diagnostics);
        diagnostics.info("PROJECT_SERVICE_STOP", null, "activeSessions", "0",
                "pendingSessions", Integer.toString(pendingCount()));
    }

    private static final class BoundStaticResourceChange {
        private final List<String> launchIds;
        private final StaticResourceChangeListener.ResourceLocation location;
        private final boolean removal;

        private BoundStaticResourceChange(List<String> launchIds,
                                           StaticResourceChangeListener.ResourceLocation location,
                                           boolean removal) {
            this.launchIds = launchIds == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(launchIds));
            this.location = location;
            this.removal = removal;
        }

        private String primaryLaunchId() {
            return launchIds.isEmpty() ? null : launchIds.get(0);
        }
    }

    private static final class StaticResourceNotification {
        private final List<String> launchIds;
        private final Path outputRoot;
        private final String resourceId;

        private StaticResourceNotification(List<String> launchIds, Path outputRoot, String resourceId) {
            this.launchIds = launchIds == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(launchIds));
            this.outputRoot = outputRoot;
            this.resourceId = resourceId;
        }
    }

    private static final class LaunchState {
        private final RunProfile profile;
        private final AgentLaunchSpec spec;
        private final SessionConnector connector;
        private final List<Path> classpathEntries;
        private final long createdAtNanos = System.nanoTime();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile HotReloadClient client;
        private volatile boolean authenticated;
        private volatile boolean hotSwapGuardActive;
        private volatile ProcessHandler processHandler;
        private volatile ProcessListener processListener;

        private LaunchState(RunProfile profile, AgentLaunchSpec spec, SessionConnector connector,
                            List<Path> classpathEntries) {
            this.profile = profile;
            this.spec = spec;
            this.connector = connector;
            this.classpathEntries = classpathEntries;
        }
    }

    private static final class ScheduledExecution {
        private final RunProfile profile;
        private final long scheduledAtNanos;

        private ScheduledExecution(RunProfile profile, long scheduledAtNanos) {
            this.profile = profile;
            this.scheduledAtNanos = scheduledAtNanos;
        }
    }
}
