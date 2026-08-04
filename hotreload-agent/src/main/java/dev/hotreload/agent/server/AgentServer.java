package dev.hotreload.agent.server;

import dev.hotreload.agent.config.AgentOptions;
import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.agent.compat.RuntimeEnvironment;
import dev.hotreload.agent.compat.RuntimeEnvironmentProbe;
import dev.hotreload.bootstrap.HotReloadBridge;
import dev.hotreload.protocol.ProtocolLimits;
import dev.hotreload.protocol.io.FrameCodec;
import dev.hotreload.protocol.message.HelloRequest;
import dev.hotreload.protocol.message.HelloResponse;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadRequest;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.session.SessionDescriptor;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class AgentServer implements AutoCloseable {
    public static final int PROTOCOL_VERSION = 1;
    public static final long DEFAULT_HELLO_TIMEOUT_MILLIS = 30_000L;
    public static final int DEFAULT_FIRST_FRAME_TIMEOUT_MILLIS = 1_000;
    public static final long DEFAULT_PENDING_PAYLOAD_LIMIT_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_UNAUTHENTICATED_CONNECTIONS = 8;
    private static final int MUTATION_QUEUE_CAPACITY = 8;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS = 2_000L;

    public interface MutationHandler {
        ReloadResponse handle(ReloadRequest request);
    }

    private final AgentOptions options;
    private final AgentSessionLogger logger;
    private final MutationHandler mutationHandler;
    private final boolean classRedefineSupported;
    private final RuntimeEnvironment environment;
    private final long helloTimeoutMillis;
    private final int firstFrameTimeoutMillis;
    private final Runnable closeListener;
    private final Object authenticationMonitor = new Object();
    private final Object writeMonitor = new Object();
    private final Object socketMonitor = new Object();
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch closeComplete = new CountDownLatch(1);
    private final AtomicInteger openSocketCount = new AtomicInteger();
    private final AtomicInteger unauthenticatedConnectionCount = new AtomicInteger();
    private final PayloadBudget payloadBudget;
    private final Set<Socket> openSockets = Collections.newSetFromMap(
            new IdentityHashMap<Socket, Boolean>());
    private final BoundedMutationExecutor mutationExecutor = new BoundedMutationExecutor(MUTATION_QUEUE_CAPACITY);
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor(
            new NamedDaemonThreadFactory("hotreload-accept"));
    private final ThreadPoolExecutor clientExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8), new NamedDaemonThreadFactory("hotreload-client"),
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new NamedDaemonThreadFactory("hotreload-deadline"));

    private volatile ServerSocket serverSocket;
    private volatile Socket authenticatedSocket;
    private volatile ScheduledFuture<?> helloDeadline;
    private volatile boolean authenticated;
    private volatile Object sessionDescriptorFileKey;
    /** E2 capability (DCEVM/JBR enhanced redefine); set before start() by the agent bootstrap. */
    private volatile boolean enhancedRedefineSupported;

    public void setEnhancedRedefineSupported(boolean value) {
        this.enhancedRedefineSupported = value;
    }

    public AgentServer(AgentOptions options, AgentSessionLogger logger, MutationHandler mutationHandler,
                       boolean classRedefineSupported) {
        this(options, logger, mutationHandler, classRedefineSupported, DEFAULT_HELLO_TIMEOUT_MILLIS,
                DEFAULT_FIRST_FRAME_TIMEOUT_MILLIS, DEFAULT_PENDING_PAYLOAD_LIMIT_BYTES, null);
    }

    AgentServer(AgentOptions options, AgentSessionLogger logger, MutationHandler mutationHandler,
                boolean classRedefineSupported, long helloTimeoutMillis) {
        this(options, logger, mutationHandler, classRedefineSupported, helloTimeoutMillis,
                DEFAULT_FIRST_FRAME_TIMEOUT_MILLIS, DEFAULT_PENDING_PAYLOAD_LIMIT_BYTES, null);
    }

    public AgentServer(AgentOptions options, AgentSessionLogger logger, MutationHandler mutationHandler,
                       boolean classRedefineSupported, long helloTimeoutMillis, Runnable closeListener) {
        this(options, logger, mutationHandler, classRedefineSupported, helloTimeoutMillis,
                DEFAULT_FIRST_FRAME_TIMEOUT_MILLIS, DEFAULT_PENDING_PAYLOAD_LIMIT_BYTES, closeListener);
    }

    AgentServer(AgentOptions options, AgentSessionLogger logger, MutationHandler mutationHandler,
                boolean classRedefineSupported, long helloTimeoutMillis, int firstFrameTimeoutMillis,
                long pendingPayloadLimitBytes) {
        this(options, logger, mutationHandler, classRedefineSupported, helloTimeoutMillis,
                firstFrameTimeoutMillis, pendingPayloadLimitBytes, null);
    }

    private AgentServer(AgentOptions options, AgentSessionLogger logger, MutationHandler mutationHandler,
                        boolean classRedefineSupported, long helloTimeoutMillis, int firstFrameTimeoutMillis,
                        long pendingPayloadLimitBytes, Runnable closeListener) {
        if (options == null) throw new NullPointerException("options");
        if (logger == null) throw new NullPointerException("logger");
        if (mutationHandler == null) throw new NullPointerException("mutationHandler");
        if (helloTimeoutMillis <= 0) throw new IllegalArgumentException("helloTimeoutMillis must be positive");
        if (firstFrameTimeoutMillis <= 0) {
            throw new IllegalArgumentException("firstFrameTimeoutMillis must be positive");
        }
        if (pendingPayloadLimitBytes <= 0) {
            throw new IllegalArgumentException("pendingPayloadLimitBytes must be positive");
        }
        this.options = options;
        this.logger = logger;
        this.mutationHandler = mutationHandler;
        this.classRedefineSupported = classRedefineSupported;
        this.environment = RuntimeEnvironmentProbe.probe(classRedefineSupported);
        this.helloTimeoutMillis = helloTimeoutMillis;
        this.firstFrameTimeoutMillis = firstFrameTimeoutMillis;
        this.payloadBudget = new PayloadBudget(pendingPayloadLimitBytes);
        this.closeListener = closeListener;
    }

    public void start() throws IOException {
        synchronized (lifecycleMonitor) {
            if (closing.get()) throw new IllegalStateException("Agent server is closed");
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("Agent server already started");
            try {
                ServerSocket socket = new ServerSocket(0, 16, InetAddress.getByName(SessionDescriptor.LOOPBACK_ADDRESS));
                serverSocket = socket;
                SessionDescriptor.authenticated(options.getLaunchId(), PROTOCOL_VERSION, socket.getLocalPort(),
                        options.getTokenBytes())
                        .writeAtomically(options.getSessionPath());
                sessionDescriptorFileKey = Files.readAttributes(options.getSessionPath(),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                logger.log(Level.INFO, "SOCKET_BOUND", fields("port", Integer.toString(socket.getLocalPort()),
                        "protocol", Integer.toString(PROTOCOL_VERSION)));
                helloDeadline = scheduler.schedule(new Runnable() {
                    @Override public void run() {
                        logger.log(Level.WARNING, "HELLO_DEADLINE_EXPIRED", Collections.<String, String>emptyMap());
                        close();
                    }
                }, helloTimeoutMillis, TimeUnit.MILLISECONDS);
                acceptExecutor.execute(new Runnable() {
                    @Override public void run() { acceptLoop(); }
                });
            } catch (IOException e) {
                close();
                throw e;
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }
    }

    public int getPort() {
        ServerSocket socket = serverSocket;
        if (socket == null) throw new IllegalStateException("Agent server is not started");
        return socket.getLocalPort();
    }

    public boolean isClosed() { return closed.get(); }

    private void acceptLoop() {
        while (!closing.get()) {
            Socket socket = null;
            CandidateTask candidateTask = null;
            try {
                socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(firstFrameTimeoutMillis);
                if (!trackSocket(socket)) {
                    socket = null;
                    continue;
                }
                if (!reserveUnauthenticatedConnection()) {
                    logger.log(Level.WARNING, "HELLO_REJECTED", fields("reason", "candidate_limit"));
                    closeSocket(socket);
                    socket = null;
                    continue;
                }
                candidateTask = new CandidateTask(socket);
                clientExecutor.execute(candidateTask);
                socket = null;
                candidateTask = null;
            } catch (RejectedExecutionException e) {
                if (candidateTask != null) candidateTask.cancelBeforeRun();
                else {
                    releaseUnauthenticatedConnection();
                    closeSocket(socket);
                }
            } catch (SocketException e) {
                if (!closing.get() && !authenticated) {
                    logger.log(Level.WARNING, "SOCKET_ACCEPT_FAILED", fields("reason", "socket"));
                }
                return;
            } catch (IOException e) {
                if (!closing.get()) logger.log(Level.WARNING, "SOCKET_ACCEPT_FAILED", fields("reason", "io"));
                closeQuietly(socket);
            }
        }
    }

    private void handleCandidate(Socket socket, CandidateTask candidateTask) {
        boolean ownsAuthenticatedSession = false;
        try {
            Object first = FrameCodec.read(socket.getInputStream(), ProtocolLimits.MAX_HELLO_FRAME_BYTES);
            if (!(first instanceof HelloRequest)) {
                logger.log(Level.WARNING, "HELLO_FAILED", fields("reason", "first_frame_not_hello"));
                return;
            }
            HelloRequest hello = (HelloRequest) first;
            if (!options.matchesToken(hello.getToken()) || !options.getLaunchId().equals(hello.getLaunchId())) {
                logger.log(Level.WARNING, "HELLO_FAILED", fields("requestId", hello.getRequestId(),
                        "reason", "authentication"));
                return;
            }
            synchronized (authenticationMonitor) {
                if (authenticated || closing.get()) {
                    logger.log(Level.WARNING, "HELLO_FAILED", fields("requestId", hello.getRequestId(),
                            "reason", "session_already_authenticated"));
                    return;
                }
                authenticated = true;
                authenticatedSocket = socket;
                ownsAuthenticatedSession = true;
            }
            candidateTask.releaseSlot();
            socket.setSoTimeout(0);
            ScheduledFuture<?> deadline = helloDeadline;
            if (deadline != null) deadline.cancel(false);
            closeQuietly(serverSocket);
            send(socket.getOutputStream(), new HelloResponse(hello.getRequestId(), PROTOCOL_VERSION,
                    classRedefineSupported, enhancedRedefineSupported, System.getProperty("java.version"),
                    HotReloadBridge.snapshotConfigurations().size()));
            Map<String, String> envFields = new LinkedHashMap<String, String>(environment.asLogFields());
            envFields.put("requestId", hello.getRequestId());
            envFields.put("redefineSupported", Boolean.toString(classRedefineSupported));
            envFields.put("enhancedRedefine", Boolean.toString(enhancedRedefineSupported));
            envFields.put("javaVersion", System.getProperty("java.version"));
            envFields.put("detail", environment.summary());
            logger.log(Level.INFO, "HELLO_OK", envFields);
            logger.log(Level.INFO, "ENVIRONMENT_PROBE", environment.asLogFields());
            readAuthenticatedRequests(socket);
        } catch (SocketTimeoutException e) {
            logger.log(Level.WARNING, "HELLO_FAILED", fields("reason", "timeout"));
        } catch (EOFException e) {
            if (ownsAuthenticatedSession) logger.log(Level.INFO, "CLIENT_EOF", Collections.<String, String>emptyMap());
            else logger.log(Level.INFO, "HELLO_FAILED", fields("reason", "eof"));
        } catch (IOException e) {
            if (!closing.get()) logger.log(Level.WARNING, ownsAuthenticatedSession ? "CLIENT_EOF" : "HELLO_FAILED",
                    fields("reason", "io"));
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, ownsAuthenticatedSession ? "CLIENT_PROTOCOL_FAILED" : "HELLO_FAILED",
                    fields("reason", "protocol"));
        } finally {
            candidateTask.releaseSlot();
            if (ownsAuthenticatedSession) close();
            else closeSocket(socket);
        }
    }

    private void readAuthenticatedRequests(final Socket socket) throws IOException {
        final OutputStream output = socket.getOutputStream();
        final InputStream input = socket.getInputStream();
        while (!closing.get()) {
            final long[] reservedBytes = new long[1];
            final Object decoded;
            try {
                decoded = FrameCodec.read(input, ProtocolLimits.MAX_FRAME_BYTES,
                        new FrameCodec.FrameLengthAcceptor() {
                            @Override public boolean accept(int frameLength) {
                                if (!tryReservePayload(frameLength)) return false;
                                reservedBytes[0] = frameLength;
                                return true;
                            }
                        });
            } catch (FrameCodec.FrameRejectedException rejected) {
                String rejectedRequestId = FrameCodec.discardAndReadRequestId(input,
                        rejected.getFrameLength());
                if (rejectedRequestId == null) throw new IllegalArgumentException("Rejected frame has no request id");
                send(output, failure(rejectedRequestId, ReloadErrorCode.PAYLOAD_TOO_LARGE));
                continue;
            } catch (IOException e) {
                releasePayload(reservedBytes[0]);
                throw e;
            } catch (RuntimeException e) {
                releasePayload(reservedBytes[0]);
                throw e;
            }
            boolean reservationOwnedByReader = true;
            try {
                if (!(decoded instanceof ReloadRequest)) {
                    throw new IllegalArgumentException("Unsupported authenticated request");
                }
                ReloadRequest request = (ReloadRequest) decoded;
                final String requestId = request.getRequestId();
                if (!options.matchesToken(request.getToken())) {
                    send(output, failure(requestId, ReloadErrorCode.AUTHENTICATION_FAILED));
                    throw new IllegalArgumentException("Unauthenticated request");
                }
                boolean accepted = mutationExecutor.submit(new PayloadMutation(request, requestId, output,
                        reservedBytes[0]));
                if (accepted) reservationOwnedByReader = false;
                if (!accepted) send(output, failure(requestId, ReloadErrorCode.RELOAD_BUSY));
            } finally {
                if (reservationOwnedByReader) releasePayload(reservedBytes[0]);
            }
        }
    }

    private final class PayloadMutation implements Runnable {
        private final ReloadRequest request;
        private final String requestId;
        private final OutputStream output;
        private final long payloadBytes;

        private PayloadMutation(ReloadRequest request, String requestId, OutputStream output,
                                long payloadBytes) {
            this.request = request;
            this.requestId = requestId;
            this.output = output;
            this.payloadBytes = payloadBytes;
        }

        long getPayloadBytes() { return payloadBytes; }

        @Override public void run() {
            try {
                ReloadResponse response;
                try {
                    response = mutationHandler.handle(request);
                    if (response == null) response = failure(requestId, ReloadErrorCode.INTERNAL_ERROR);
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "MUTATION_FAILED", fields("requestId", requestId,
                            "reason", e.getClass().getSimpleName()));
                    response = failure(requestId, ReloadErrorCode.INTERNAL_ERROR);
                } catch (LinkageError e) {
                    logger.log(Level.SEVERE, "MUTATION_FAILED", fields("requestId", requestId,
                            "reason", e.getClass().getSimpleName()));
                    response = failure(requestId, ReloadErrorCode.INTERNAL_ERROR);
                } catch (InternalError e) {
                    logger.log(Level.SEVERE, "MUTATION_FAILED", fields("requestId", requestId,
                            "reason", e.getClass().getSimpleName()));
                    response = failure(requestId, ReloadErrorCode.INTERNAL_ERROR);
                }
                try {
                    send(output, response);
                } catch (IOException e) {
                    close();
                }
            } finally {
                releasePayload(payloadBytes);
            }
        }
    }

    private boolean tryReservePayload(long payloadBytes) {
        return payloadBudget.tryReserve(payloadBytes);
    }

    private void releasePayload(long payloadBytes) {
        payloadBudget.release(payloadBytes);
    }

    private void send(OutputStream output, Object response) throws IOException {
        synchronized (writeMonitor) {
            FrameCodec.write(output, response);
        }
    }

    private static ReloadResponse failure(String requestId, ReloadErrorCode errorCode) {
        return new ReloadResponse(requestId, OperationStatus.FAILED, errorCode, "",
                Collections.emptyList());
    }

    @Override public void close() {
        boolean owner;
        synchronized (lifecycleMonitor) {
            owner = closing.compareAndSet(false, true);
        }
        if (!owner) {
            awaitCloseCompletion();
            return;
        }
        try {
            closeResources();
        } finally {
            try {
                if (closeListener == null) logger.close();
            } finally {
                closed.set(true);
                closeComplete.countDown();
            }
        }
        if (closeListener != null) closeListener.run();
    }

    private void closeResources() {
        long startedAt = System.nanoTime();
        boolean activeSessionBeforeClose = authenticated;
        ScheduledFuture<?> deadline = helloDeadline;
        if (deadline != null) deadline.cancel(false);
        closeQuietly(serverSocket);
        closeAllSockets();
        authenticatedSocket = null;
        authenticated = false;
        try {
            // shutdownNow() interrupts the calling client worker on JDK 8; delete the
            // descriptor before that interrupt can make NIO reads fail with ClosedByInterruptException.
            deleteSessionDescriptorIfOwned();
        } catch (IOException e) {
            String step = e instanceof DescriptorCleanupException
                    ? ((DescriptorCleanupException) e).getStep() : "unknown";
            String type = e instanceof DescriptorCleanupException
                    ? ((DescriptorCleanupException) e).getFailureType() : e.getClass().getSimpleName();
            logger.log(Level.WARNING, "SESSION_DESCRIPTOR_DELETE_FAILED",
                    fields("reason", "io", "step", step, "detail", type));
        }
        scheduler.shutdownNow();
        acceptExecutor.shutdownNow();
        releaseDroppedCandidates(clientExecutor.shutdownNow());
        List<Runnable> droppedMutations = mutationExecutor.shutdownNow();
        releaseDroppedPayloads(droppedMutations);
        long executorDeadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS);
        boolean acceptTerminated = awaitTermination(acceptExecutor, executorDeadline, "hotreload-accept-");
        boolean clientTerminated = awaitTermination(clientExecutor, executorDeadline, "hotreload-client-");
        boolean schedulerTerminated = awaitTermination(scheduler, executorDeadline, "hotreload-deadline-");
        boolean mutationTerminated = mutationExecutor.awaitTermination(remainingNanos(executorDeadline),
                TimeUnit.NANOSECONDS);
        logger.log(Level.INFO, "EXECUTOR_SHUTDOWN", fields("remainingQueue",
                Integer.toString(mutationExecutor.getQueueSize()), "remainingThreads", activeOwnedThreads(),
                "acceptTerminated", Boolean.toString(acceptTerminated),
                "clientTerminated", Boolean.toString(clientTerminated),
                "schedulerTerminated", Boolean.toString(schedulerTerminated),
                "mutationTerminated", Boolean.toString(mutationTerminated)));
        Map<String, String> snapshot = fields("activeSession", Boolean.toString(authenticated),
                "openSocket", Integer.toString(openSocketCount.get()),
                "unauthenticatedConnections", Integer.toString(unauthenticatedConnectionCount.get()),
                "queueSize", Integer.toString(mutationExecutor.getQueueSize()),
                "activeSessionBeforeClose", Boolean.toString(activeSessionBeforeClose),
                "pendingPayloadBytes", Long.toString(payloadBudget.getRetainedBytes()),
                "trackedConfigurations", Integer.toString(HotReloadBridge.snapshotConfigurations().size()),
                "trackedResources", Integer.toString(trackedResourceCount()),
                "recentEvents", Integer.toString(logger.getRecentEventCount()),
                "executorState", executorState(acceptTerminated, clientTerminated,
                        schedulerTerminated, mutationTerminated),
                "ownedThreads", activeOwnedThreads(),
                "durationMs", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
        snapshot.put("usedHeapBytes", Long.toString(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
        snapshot.put("liveThreads", Integer.toString(Thread.activeCount()));
        logger.log(Level.INFO, "RESOURCE_SNAPSHOT", snapshot);
        logger.log(Level.INFO, "CLEANUP_COMPLETE", fields("durationMs",
                Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))));
    }

    private void awaitCloseCompletion() {
        if (Thread.currentThread().getName().startsWith("hotreload-")) return;
        try {
            closeComplete.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseDroppedPayloads(List<Runnable> droppedMutations) {
        for (Runnable mutation : droppedMutations) {
            if (mutation instanceof PayloadMutation) {
                releasePayload(((PayloadMutation) mutation).getPayloadBytes());
            }
        }
    }

    private void releaseDroppedCandidates(List<Runnable> droppedCandidates) {
        for (Runnable candidate : droppedCandidates) {
            if (candidate instanceof CandidateTask) {
                ((CandidateTask) candidate).cancelBeforeRun();
            }
        }
    }

    private void deleteSessionDescriptorIfOwned() throws IOException {
        if (Files.isSymbolicLink(options.getSessionPath())
                || !Files.isRegularFile(options.getSessionPath(), LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(options.getSessionPath(),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new DescriptorCleanupException("attributes", e);
        }
        Object expectedFileKey = sessionDescriptorFileKey;
        if (expectedFileKey != null && !expectedFileKey.equals(attributes.fileKey())) return;
        SessionDescriptor descriptor;
        try {
            descriptor = SessionDescriptor.read(options.getSessionPath());
        } catch (IOException e) {
            throw new DescriptorCleanupException("read", e);
        }
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(options.getTokenBytes());
        if (!options.getLaunchId().equals(descriptor.getLaunchId()) || !descriptor.verifies(token)) return;
        try {
            Files.deleteIfExists(options.getSessionPath());
        } catch (IOException e) {
            throw new DescriptorCleanupException("delete", e);
        }
    }

    private int trackedResourceCount() {
        int count = 0;
        for (dev.hotreload.bootstrap.ConfigurationHandle handle
                : HotReloadBridge.snapshotConfigurations()) {
            count += handle.getResourceMetadata().size();
        }
        return count;
    }

    private static String executorState(boolean acceptTerminated, boolean clientTerminated,
                                        boolean schedulerTerminated, boolean mutationTerminated) {
        return "accept:" + state(acceptTerminated) + ",client:" + state(clientTerminated)
                + ",scheduler:" + state(schedulerTerminated) + ",mutation:" + state(mutationTerminated);
    }

    private static String state(boolean terminated) { return terminated ? "TERMINATED" : "RUNNING"; }

    private void closeSocket(Socket socket) {
        if (socket == null) return;
        boolean tracked;
        synchronized (socketMonitor) {
            tracked = openSockets.remove(socket);
        }
        closeQuietly(socket);
        if (tracked) openSocketCount.decrementAndGet();
    }

    private boolean trackSocket(Socket socket) {
        synchronized (socketMonitor) {
            if (closing.get()) {
                closeQuietly(socket);
                return false;
            }
            openSockets.add(socket);
            openSocketCount.incrementAndGet();
            return true;
        }
    }

    private boolean reserveUnauthenticatedConnection() {
        while (true) {
            int current = unauthenticatedConnectionCount.get();
            if (current >= MAX_UNAUTHENTICATED_CONNECTIONS) return false;
            if (unauthenticatedConnectionCount.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releaseUnauthenticatedConnection() {
        while (true) {
            int current = unauthenticatedConnectionCount.get();
            if (current <= 0) return;
            if (unauthenticatedConnectionCount.compareAndSet(current, current - 1)) return;
        }
    }

    private void closeAllSockets() {
        Socket[] snapshot;
        synchronized (socketMonitor) {
            snapshot = openSockets.toArray(new Socket[openSockets.size()]);
        }
        for (Socket socket : snapshot) closeSocket(socket);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Shutdown is best-effort; the resource snapshot records remaining owned resources.
        }
    }

    private static String activeOwnedThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("hotreload-")) count++;
        }
        return Integer.toString(count);
    }

    private static boolean awaitTermination(ExecutorService executor, long deadlineNanos,
                                            String currentThreadPrefix) {
        if (Thread.currentThread().getName().startsWith(currentThreadPrefix)) return executor.isTerminated();
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0L) return executor.isTerminated();
        try {
            return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) fields.put(keyValues[i], keyValues[i + 1]);
        return fields;
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private NamedDaemonThreadFactory(String prefix) { this.prefix = prefix; }

        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class DescriptorCleanupException extends IOException {
        private final String step;

        private DescriptorCleanupException(String step, IOException cause) {
            super(cause);
            this.step = step;
        }

        private String getStep() { return step; }

        private String getFailureType() {
            Throwable failure = getCause();
            for (int depth = 0; depth < 4 && failure != null && failure.getCause() != null; depth++) {
                failure = failure.getCause();
            }
            return failure == null ? "unknown" : failure.getClass().getSimpleName();
        }
    }

    private final class CandidateTask implements Runnable {
        private final Socket socket;
        private final AtomicBoolean slotOwned = new AtomicBoolean(true);

        private CandidateTask(Socket socket) { this.socket = socket; }

        @Override public void run() { handleCandidate(socket, this); }

        private void releaseSlot() {
            if (slotOwned.compareAndSet(true, false)) releaseUnauthenticatedConnection();
        }

        private void cancelBeforeRun() {
            releaseSlot();
            closeSocket(socket);
        }
    }
}
