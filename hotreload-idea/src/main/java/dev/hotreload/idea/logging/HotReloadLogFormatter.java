package dev.hotreload.idea.logging;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class HotReloadLogFormatter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private HotReloadLogFormatter() { }

    public static String format(HotReloadLogEvent event) {
        return format(event, ZoneId.systemDefault());
    }

    public static String format(HotReloadLogEvent event, ZoneId zoneId) {
        StringBuilder line = new StringBuilder(160);
        line.append(TIME.format(event.getTimestamp().atZone(zoneId)));
        line.append(' ').append(HotReloadChineseMessages.levelTitle(event.getLevel()));
        line.append(' ').append(HotReloadChineseMessages.eventTitle(event.getEvent()));
        // keep raw event code for searchability
        if (!event.getEvent().equals(HotReloadChineseMessages.eventTitle(event.getEvent()))) {
            line.append('[').append(event.getEvent()).append(']');
        }
        line.append(" 会话=").append(event.getLaunchId());
        String details = HotReloadChineseMessages.formatDetails(event.getEvent(), event.getDetails());
        if (details != null && !details.isEmpty()) {
            line.append(' ').append(details);
        } else if (!event.getDetails().isEmpty()) {
            line.append(' ').append(event.getDetails());
        }
        return line.toString();
    }
}
