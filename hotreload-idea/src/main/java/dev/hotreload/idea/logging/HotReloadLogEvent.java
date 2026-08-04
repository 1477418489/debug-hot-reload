package dev.hotreload.idea.logging;

import java.time.Instant;
import java.util.Objects;

public final class HotReloadLogEvent {
    public enum Level { INFO, WARN }

    private final Instant timestamp;
    private final Level level;
    private final String event;
    private final String launchId;
    private final String details;

    public HotReloadLogEvent(Instant timestamp, Level level, String event, String launchId, String details) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.level = Objects.requireNonNull(level, "level");
        this.event = Objects.requireNonNull(event, "event");
        this.launchId = launchId == null || launchId.isEmpty() ? "none" : launchId;
        this.details = details == null ? "" : details;
    }

    public Instant getTimestamp() { return timestamp; }
    public Level getLevel() { return level; }
    public String getEvent() { return event; }
    public String getLaunchId() { return launchId; }
    public String getDetails() { return details; }
}
