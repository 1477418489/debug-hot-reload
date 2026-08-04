package dev.hotreload.idea.client;

import dev.hotreload.idea.run.AgentLaunchSpec;
import dev.hotreload.protocol.message.HelloResponse;
import dev.hotreload.protocol.session.SessionDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class SessionConnector implements AutoCloseable {
    private static final int PROTOCOL_VERSION = 1;

    interface EventSink {
        void record(String event, String launchId);
    }

    interface Listener {
        void connected(HotReloadClient client, HelloResponse response);
        void deadlineExpired();
    }

    private final AgentLaunchSpec spec;
    private final ScheduledExecutorService scheduler;
    private final EventSink events;
    private final long pollMillis;
    private final long timeoutNanos;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Listener listener;
    private volatile long deadlineNanos;
    private volatile ScheduledFuture<?> scheduledPoll;
    private volatile HotReloadClient connectingClient;

    SessionConnector(AgentLaunchSpec spec, ScheduledExecutorService scheduler, EventSink events,
                     long pollMillis, long timeoutMillis) {
        if (spec == null) throw new NullPointerException("spec");
        if (scheduler == null) throw new NullPointerException("scheduler");
        if (events == null) throw new NullPointerException("events");
        if (pollMillis <= 0 || timeoutMillis <= 0) throw new IllegalArgumentException("timeouts must be positive");
        this.spec = spec;
        this.scheduler = scheduler;
        this.events = events;
        this.pollMillis = pollMillis;
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    void start(Listener listener) {
        if (listener == null) throw new NullPointerException("listener");
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("connector already started");
        this.listener = listener;
        this.deadlineNanos = System.nanoTime() + timeoutNanos;
        schedulePoll(0L);
    }

    boolean isClosed() {
        return closed.get();
    }

    private void poll() {
        if (closed.get()) return;
        if (System.nanoTime() >= deadlineNanos) {
            expire();
            return;
        }
        if (!SessionDescriptorFiles.isSafePath(spec.getSessionPath())
                || Files.isSymbolicLink(spec.getSessionPath())
                || !Files.isRegularFile(spec.getSessionPath(), LinkOption.NOFOLLOW_LINKS)) {
            schedulePoll(pollMillis);
            return;
        }
        try {
            SessionDescriptor descriptor = SessionDescriptor.read(spec.getSessionPath());
            if (!spec.getLaunchId().equals(descriptor.getLaunchId())
                    || descriptor.getProtocol() != PROTOCOL_VERSION
                    || !descriptor.verifies(spec.getToken())) {
                events.record("SESSION_DESCRIPTOR_REJECTED", spec.getLaunchId());
                schedulePoll(pollMillis);
                return;
            }
            connect(descriptor);
        } catch (IOException | RuntimeException failure) {
            events.record("SESSION_DESCRIPTOR_READ_FAILED", spec.getLaunchId());
            schedulePoll(pollMillis);
        }
    }

    private void connect(SessionDescriptor descriptor) {
        HotReloadClient client = new HotReloadClient(descriptor, spec.getToken(), spec.getLaunchId());
        connectingClient = client;
        client.connect().whenComplete((response, failure) -> {
            if (failure != null) {
                client.close();
                clearConnectingClient(client);
                if (!closed.get()) {
                    events.record("HELLO_RETRY", spec.getLaunchId());
                    schedulePoll(pollMillis);
                }
                return;
            }
            clearConnectingClient(client);
            if (closed.get()) {
                client.close();
                return;
            }
            closed.set(true);
            cancelScheduledPoll();
            events.record("HELLO_OK", spec.getLaunchId());
            listener.connected(client, response);
        });
    }

    private void clearConnectingClient(HotReloadClient client) {
        if (connectingClient == client) connectingClient = null;
    }

    private void schedulePoll(long delayMillis) {
        if (closed.get()) return;
        scheduledPoll = scheduler.schedule(this::poll, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void expire() {
        if (!closed.compareAndSet(false, true)) return;
        cancelScheduledPoll();
        HotReloadClient client = connectingClient;
        connectingClient = null;
        if (client != null) client.close();
        events.record("HELLO_DEADLINE_EXPIRED", spec.getLaunchId());
        listener.deadlineExpired();
    }

    private void cancelScheduledPoll() {
        ScheduledFuture<?> poll = scheduledPoll;
        scheduledPoll = null;
        if (poll != null) poll.cancel(false);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancelScheduledPoll();
        HotReloadClient client = connectingClient;
        connectingClient = null;
        if (client != null) client.close();
    }
}
