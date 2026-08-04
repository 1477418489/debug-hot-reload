package dev.hotreload.idea.logging;

import com.intellij.openapi.diagnostic.Logger;

import java.time.Instant;

public final class PluginSessionDiagnostics {
    private static final Logger LOG = Logger.getInstance(PluginSessionDiagnostics.class);
    private static final int MAX_VALUE_LENGTH = 4096;
    private final String projectId;
    private final HotReloadLogBuffer buffer;

    public PluginSessionDiagnostics(String projectId) {
        this(projectId, null);
    }

    public PluginSessionDiagnostics(String projectId, HotReloadLogBuffer buffer) {
        this.projectId = sanitize(projectId == null ? "unknown" : projectId);
        this.buffer = buffer;
    }

    public void info(String event, String launchId, String... fields) {
        publish(HotReloadLogEvent.Level.INFO, event, launchId, fields);
    }

    public void warn(String event, String launchId, String... fields) {
        publish(HotReloadLogEvent.Level.WARN, event, launchId, fields);
    }

    private void publish(HotReloadLogEvent.Level level, String event, String launchId, String... fields) {
        String line = format(event, launchId, fields);
        if (level == HotReloadLogEvent.Level.WARN) {
            LOG.warn(line);
        } else {
            LOG.info(line);
        }
        if (buffer != null) {
            buffer.append(new HotReloadLogEvent(Instant.now(), level, sanitize(event),
                    launchId == null ? "none" : sanitize(launchId), details(fields)));
        }
    }

    private String format(String event, String launchId, String... fields) {
        StringBuilder line = new StringBuilder(160);
        append(line, "event", event);
        append(line, "project", projectId);
        append(line, "launchId", launchId == null ? "none" : launchId);
        append(line, "thread", Thread.currentThread().getName());
        for (int i = 0; fields != null && i + 1 < fields.length; i += 2) {
            append(line, fields[i], fields[i + 1]);
        }
        return line.toString();
    }

    private static String details(String... fields) {
        if (fields == null || fields.length == 0) return "";
        // Prefer operational fields first so truncation keeps the useful signal.
        String[] preferred = new String[] {
                "status", "errorCode", "itemCount", "successCount", "skippedCount",
                "failedCount",
                "itemId", "requestId", "resourceId", "classCount", "reason", "result",
                "stage", "verdict", "probe", "message", "detail"
        };
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if (fields[i] == null) continue;
            map.put(fields[i], fields[i + 1]);
        }
        StringBuilder line = new StringBuilder(64);
        for (String key : preferred) {
            if (!map.containsKey(key)) continue;
            if (line.length() > 0) line.append(' ');
            line.append(sanitize(key)).append('=').append(sanitize(map.remove(key)));
        }
        for (java.util.Map.Entry<String, String> entry : map.entrySet()) {
            if (line.length() > 0) line.append(' ');
            line.append(sanitize(entry.getKey())).append('=').append(sanitize(entry.getValue()));
        }
        return line.toString();
    }

    private static void append(StringBuilder line, String key, String value) {
        if (line.length() > 0) line.append(' ');
        line.append(sanitize(key)).append('=').append(sanitize(value));
    }

    private static String sanitize(String value) {
        if (value == null) return "null";
        String sanitized = value.replaceAll("[\\r\\n\\t ]+", "_");
        return sanitized.length() <= MAX_VALUE_LENGTH
                ? sanitized : sanitized.substring(0, MAX_VALUE_LENGTH);
    }
}
