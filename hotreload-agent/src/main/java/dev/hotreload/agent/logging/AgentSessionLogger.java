package dev.hotreload.agent.logging;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public final class AgentSessionLogger implements AutoCloseable {
    public static final int DEFAULT_FILE_BYTES = 5 * 1024 * 1024;
    public static final int DEFAULT_FILE_COUNT = 3;

    private static final int MAX_VALUE_CHARS = 2048;
    private static final Set<String> ALLOWED_FIELDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "requestId", "itemId", "resultCode", "durationMs", "queueSize", "pendingPayloadBytes",
            "activeSession", "activeSessionBeforeClose", "openSocket", "unauthenticatedConnections",
            "trackedConfigurations", "trackedResources",
            "recentEvents", "ownedThreads", "executorState", "usedHeapBytes", "liveThreads",
             "classCount", "classNames", "payloadBytes", "resourceHash", "resourceId", "namespace", "ownedCount",
             "configurationCount", "ownerCount", "matchedOwnerCount", "typeName", "loaderName", "changeKind", "rebind", "definedCount", "redefinedCount",
             "remainingQueue", "remainingThreads", "step", "reason", "detail", "port", "protocol",
             "javaVersion", "redefineSupported", "closeFailures", "acceptTerminated",
             "clientTerminated", "schedulerTerminated", "mutationTerminated",
             "stage", "verdict", "probe", "proxy", "advisorCount", "indexAwareAdvice",
             "annotationIndex", "bridgeIndexed", "reflect", "spring", "index", "alias",
             "jdk", "vendor", "springBoot", "mybatis", "mybatisPlus", "servletApi",
             "classRedefine", "capabilities", "contentType")));

    private final String launchId;
    private final FileHandler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int eventCount;

    public AgentSessionLogger(String launchId, Path logPattern) throws IOException {
        this(launchId, logPattern, DEFAULT_FILE_BYTES, DEFAULT_FILE_COUNT);
    }

    AgentSessionLogger(String launchId, Path logPattern, int fileBytes, int fileCount) throws IOException {
        if (launchId == null || launchId.isEmpty()) throw new IllegalArgumentException("launchId must not be empty");
        if (logPattern == null || !logPattern.isAbsolute()) throw new IllegalArgumentException("log path must be absolute");
        if (fileBytes <= 0 || fileCount <= 0) throw new IllegalArgumentException("log limits must be positive");
        this.launchId = sanitize(launchId);
        this.handler = new FileHandler(logPattern.toString(), fileBytes, fileCount, true);
        this.handler.setLevel(Level.ALL);
        this.handler.setFormatter(new SingleLineFormatter());
    }

    public synchronized void log(Level level, String event, Map<String, String> fields) {
        if (closed.get()) return;
        if (eventCount < 10000) eventCount++;
        LogRecord record = record(level, event, fields);
        handler.publish(record);
        handler.flush();
    }

    public synchronized int getRecentEventCount() {
        return eventCount;
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        LogRecord closing = record(Level.INFO, "LOGGER_CLOSE", Collections.<String, String>emptyMap());
        handler.publish(closing);
        handler.flush();
        handler.close();
    }

    private LogRecord record(Level level, String event, Map<String, String> fields) {
        if (level == null) throw new NullPointerException("level");
        if (event == null || event.isEmpty()) throw new IllegalArgumentException("event must not be empty");
        StringBuilder message = new StringBuilder(256);
        append(message, "time", Instant.now().toString());
        append(message, "level", level.getName());
        append(message, "launchId", launchId);
        append(message, "thread", Thread.currentThread().getName());
        append(message, "event", event);
        if (fields != null) {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (ALLOWED_FIELDS.contains(field.getKey()) && field.getValue() != null) {
                    append(message, field.getKey(), field.getValue());
                }
            }
        }
        return new LogRecord(level, message.toString());
    }

    private static void append(StringBuilder target, String key, String value) {
        if (target.length() > 0) target.append(' ');
        target.append(key).append('=').append(sanitize(value));
    }

    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_VALUE_CHARS));
        int length = Math.min(value.length(), MAX_VALUE_CHARS);
        for (int i = 0; i < length; i++) {
            char character = value.charAt(i);
            result.append(Character.isWhitespace(character) || character == '=' ? '_' : character);
        }
        return result.toString();
    }

    private static final class SingleLineFormatter extends Formatter {
        @Override public String format(LogRecord record) {
            return record.getMessage() + System.lineSeparator();
        }
    }
}
