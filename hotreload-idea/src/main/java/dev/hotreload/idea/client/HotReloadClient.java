package dev.hotreload.idea.client;

import dev.hotreload.protocol.io.FrameCodec;
import dev.hotreload.protocol.message.ClassReloadRequest;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.HelloRequest;
import dev.hotreload.protocol.message.HelloResponse;
import dev.hotreload.protocol.message.MapperReloadRequest;
import dev.hotreload.protocol.message.MapperUpdate;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.message.ResourceReloadRequest;
import dev.hotreload.protocol.session.SessionDescriptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HotReloadClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 1_000;
    /** 全量 initHandlerMethods 重建等重操作可能超过 10s；超时即失步会杀死整个会话。 */
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int REQUEST_QUEUE_CAPACITY = 8;
    /** 上一请求超时后迟到的响应数量上限；超过视为协议破坏。 */
    private static final int MAX_STALE_RESPONSES = 4;

    private final SessionDescriptor descriptor;
    private final String token;
    private final String launchId;
    private final Object socketMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(REQUEST_QUEUE_CAPACITY), new ClientThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    private volatile Socket socket;
    private volatile boolean authenticated;

    public HotReloadClient(SessionDescriptor descriptor, String token, String launchId) {
        if (descriptor == null) throw new NullPointerException("descriptor");
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("token must not be empty");
        if (launchId == null || launchId.isEmpty()) throw new IllegalArgumentException("launchId must not be empty");
        if (!launchId.equals(descriptor.getLaunchId())) throw new IllegalArgumentException("launchId does not match descriptor");
        this.descriptor = descriptor;
        this.token = token;
        this.launchId = launchId;
    }

    public CompletableFuture<HelloResponse> connect() {
        return submit(() -> {
            if (authenticated) throw new IllegalStateException("Client is already authenticated");
            Socket candidate = new Socket();
            synchronized (socketMonitor) {
                if (closed.get()) throw new IllegalStateException("Client is closed");
                socket = candidate;
            }
            try {
                candidate.connect(new InetSocketAddress(descriptor.getAddress(), descriptor.getPort()),
                        CONNECT_TIMEOUT_MILLIS);
                candidate.setSoTimeout(READ_TIMEOUT_MILLIS);
                candidate.setTcpNoDelay(true);
                String requestId = UUID.randomUUID().toString();
                FrameCodec.write(candidate.getOutputStream(), new HelloRequest(requestId, token, launchId));
                Object value = FrameCodec.read(candidate.getInputStream());
                if (!(value instanceof HelloResponse)) throw new IOException("Agent did not return HelloResponse");
                HelloResponse response = (HelloResponse) value;
                if (!requestId.equals(response.getRequestId())
                        || response.getProtocolVersion() != descriptor.getProtocol()) {
                    throw new IOException("Agent HelloResponse does not match the launch");
                }
                authenticated = true;
                return response;
            } catch (Exception e) {
                closeSocket(candidate);
                synchronized (socketMonitor) {
                    if (socket == candidate) socket = null;
                }
                throw e;
            }
        });
    }

    public CompletableFuture<ReloadResponse> reloadMapper(MapperUpdate update) {
        if (update == null) throw new NullPointerException("update");
        return submit(() -> {
            String requestId = UUID.randomUUID().toString();
            return exchange(requestId, new MapperReloadRequest(requestId, token, update));
        });
    }

    public CompletableFuture<ReloadResponse> reloadResource(String resourcePath, byte[] content, String contentType) {
        if (resourcePath == null || resourcePath.isEmpty()) throw new IllegalArgumentException("resourcePath");
        if (content == null) throw new IllegalArgumentException("content");
        return submit(() -> {
            String requestId = UUID.randomUUID().toString();
            return exchange(requestId, new ResourceReloadRequest(
                    requestId, token, resourcePath, content,
                    contentType == null ? "properties" : contentType));
        });
    }

    public CompletableFuture<ReloadResponse> reloadClasses(List<ClassUpdate> updates) {
        return submit(() -> {
            String requestId = UUID.randomUUID().toString();
            return exchange(requestId, new ClassReloadRequest(requestId, token, updates));
        });
    }

    public boolean isClosed() { return closed.get(); }
    public int getQueueSize() { return executor.getQueue().size(); }

    private ReloadResponse exchange(String requestId, Object request) throws IOException {
        if (!authenticated) throw new IllegalStateException("Client is not authenticated");
        Socket current = socket;
        if (current == null || current.isClosed()) throw new IOException("Agent socket is closed");
        FrameCodec.write(current.getOutputStream(), request);
        // 单连接顺序协议：上一请求读超时后，其响应可能在本请求期间迟到。
        // 丢弃有限个 requestId 不匹配的迟到响应而不是立即断开，避免一次慢操作永久杀死会话。
        for (int stale = 0; stale <= MAX_STALE_RESPONSES; stale++) {
            Object value = FrameCodec.read(current.getInputStream());
            if (!(value instanceof ReloadResponse)) {
                close();
                throw new IOException("Agent response does not match the request");
            }
            ReloadResponse response = (ReloadResponse) value;
            if (requestId.equals(response.getRequestId())) return response;
        }
        close();
        throw new IOException("Agent response does not match the request");
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        if (closed.get()) {
            future.completeExceptionally(new IllegalStateException("Client is closed"));
            return future;
        }
        PendingTask<T> task = new PendingTask<T>(supplier, future);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        authenticated = false;
        Socket current;
        synchronized (socketMonitor) {
            current = socket;
            socket = null;
        }
        closeSocket(current);
        List<Runnable> dropped = executor.shutdownNow();
        for (Runnable runnable : dropped) {
            if (runnable instanceof PendingTask) {
                ((PendingTask<?>) runnable).future.completeExceptionally(new IllegalStateException("Client closed"));
            }
        }
        if (!Thread.currentThread().getName().startsWith("hotreload-idea-client-")) {
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static final class PendingTask<T> implements Runnable {
        private final CheckedSupplier<T> supplier;
        private final CompletableFuture<T> future;

        private PendingTask(CheckedSupplier<T> supplier, CompletableFuture<T> future) {
            this.supplier = supplier;
            this.future = future;
        }

        @Override public void run() {
            if (future.isDone()) return;
            try {
                future.complete(supplier.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        }
    }

    private static final class ClientThreadFactory implements ThreadFactory {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "hotreload-idea-client-1");
            thread.setDaemon(true);
            return thread;
        }
    }
}
