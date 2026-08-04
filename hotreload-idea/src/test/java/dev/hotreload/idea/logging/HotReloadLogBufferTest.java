package dev.hotreload.idea.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotReloadLogBufferTest {
    @Test
    void keepsOnlyNewestEventsWhenCapacityExceeded() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(3);
        buffer.append(event("E1"));
        buffer.append(event("E2"));
        buffer.append(event("E3"));
        buffer.append(event("E4"));

        List<HotReloadLogEvent> snapshot = buffer.snapshot();
        assertEquals(3, snapshot.size());
        assertEquals("E2", snapshot.get(0).getEvent());
        assertEquals("E3", snapshot.get(1).getEvent());
        assertEquals("E4", snapshot.get(2).getEvent());
    }

    @Test
    void clearEmptiesSnapshotAndNotifiesListeners() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(10);
        AtomicInteger cleared = new AtomicInteger();
        buffer.addListener(new HotReloadLogBuffer.Listener() {
            @Override public void onAppend(HotReloadLogEvent event) { }
            @Override public void onCleared() { cleared.incrementAndGet(); }
        });
        buffer.append(event("E1"));
        buffer.clear();
        assertTrue(buffer.snapshot().isEmpty());
        assertEquals(1, cleared.get());
    }

    @Test
    void notifiesListenersOnAppend() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(10);
        List<String> seen = new ArrayList<String>();
        buffer.addListener(new HotReloadLogBuffer.Listener() {
            @Override public void onAppend(HotReloadLogEvent event) { seen.add(event.getEvent()); }
            @Override public void onCleared() { }
        });
        buffer.append(event("A"));
        buffer.append(event("B"));
        assertEquals(List.of("A", "B"), seen);
    }

    @Test
    void defaultCapacityIs1000() {
        assertEquals(1000, new HotReloadLogBuffer().capacity());
    }

    @Test void resizingRetainsNewestEventsAndNotifiesListeners() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(4);
        buffer.append(event("E1"));
        buffer.append(event("E2"));
        buffer.append(event("E3"));
        buffer.append(event("E4"));
        List<List<String>> resets = new ArrayList<List<String>>();
        buffer.addListener(new HotReloadLogBuffer.Listener() {
            @Override public void onAppend(HotReloadLogEvent event) { }
            @Override public void onCleared() { }
            @Override public void onReset(List<HotReloadLogEvent> events) {
                List<String> names = new ArrayList<String>();
                for (HotReloadLogEvent event : events) names.add(event.getEvent());
                resets.add(names);
            }
        });

        buffer.setCapacity(2);

        assertEquals(2, buffer.capacity());
        assertEquals(List.of("E3", "E4"), resets.get(0));
        assertEquals(List.of("E3", "E4"), buffer.snapshot().stream()
                .map(HotReloadLogEvent::getEvent).toList());
    }

    @Test void refreshNotifiesListenersWithoutChangingContents() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(3);
        buffer.append(event("E1"));
        AtomicInteger resets = new AtomicInteger();
        buffer.addListener(new HotReloadLogBuffer.Listener() {
            @Override public void onAppend(HotReloadLogEvent event) { }
            @Override public void onCleared() { }
            @Override public void onReset(List<HotReloadLogEvent> events) {
                assertEquals(List.of("E1"), events.stream()
                        .map(HotReloadLogEvent::getEvent).toList());
                resets.incrementAndGet();
            }
        });

        buffer.refreshListeners();

        assertEquals(1, resets.get());
        assertEquals(1, buffer.snapshot().size());
    }

    @Test void listenerRegistrationAndInitialSnapshotHaveNoGap() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(3);
        buffer.append(event("before"));
        List<String> appended = new ArrayList<String>();
        HotReloadLogBuffer.Listener listener = new HotReloadLogBuffer.Listener() {
            @Override public void onAppend(HotReloadLogEvent event) {
                appended.add(event.getEvent());
            }
            @Override public void onCleared() { }
        };

        List<HotReloadLogEvent> initial = buffer.addListenerAndSnapshot(listener);
        buffer.append(event("after"));

        assertEquals(List.of("before"), initial.stream()
                .map(HotReloadLogEvent::getEvent).toList());
        assertEquals(List.of("after"), appended);
    }

    @Test void failingListenerCannotBreakLoggingOrBlockOtherListeners() {
        HotReloadLogBuffer buffer = new HotReloadLogBuffer(3);
        buffer.addListener(new HotReloadLogBuffer.Listener() {
            @Override public void onAppend(HotReloadLogEvent event) {
                throw new IllegalStateException("broken UI listener");
            }
            @Override public void onCleared() { }
        });
        AtomicInteger delivered = new AtomicInteger();
        buffer.addListener(new HotReloadLogBuffer.Listener() {
            @Override public void onAppend(HotReloadLogEvent event) {
                delivered.incrementAndGet();
            }
            @Override public void onCleared() { }
        });

        assertDoesNotThrow(() -> buffer.append(event("E1")));
        assertEquals(1, delivered.get());
        assertEquals(1, buffer.snapshot().size());
    }

    private static HotReloadLogEvent event(String name) {
        return new HotReloadLogEvent(Instant.parse("2026-07-24T06:00:00Z"), HotReloadLogEvent.Level.INFO,
                name, "none", "");
    }
}
