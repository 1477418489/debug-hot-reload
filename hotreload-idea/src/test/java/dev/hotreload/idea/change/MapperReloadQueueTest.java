package dev.hotreload.idea.change;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MapperReloadQueueTest {
    @Test void coalescesRepeatedChangesForOneFile() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        AtomicReference<String> routedLaunch = new AtomicReference<String>();
        AtomicReference<Path> routedOutput = new AtomicReference<Path>();
        MapperReloadQueue queue = new MapperReloadQueue(scheduler, 25, 8,
                (launchId, root, output, file) -> {
                    routedLaunch.set(launchId);
                    routedOutput.set(output);
                    count.incrementAndGet();
                    fired.countDown();
                });
        try {
            Path output = Paths.get("C:/classes");
            queue.schedule("launch-a", Paths.get("C:/resources"), output,
                    Paths.get("C:/resources/mapper.xml"));
            queue.schedule("launch-b", Paths.get("C:/resources"), output,
                    Paths.get("C:/resources/mapper.xml"));
            assertTrue(fired.await(2, TimeUnit.SECONDS));
            assertEquals(1, count.get());
            assertEquals("launch-b", routedLaunch.get());
            assertEquals(output.toAbsolutePath().normalize(), routedOutput.get());
            assertEquals(0, queue.getPendingCount());
        } finally {
            queue.close();
            scheduler.shutdownNow();
        }
    }

    @Test void rejectedSchedulerDoesNotLeaveAStuckPendingEntry() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.shutdownNow();
        MapperReloadQueue queue = new MapperReloadQueue(scheduler, 25, 8,
                (launchId, root, output, file) -> { });
        try {
            assertFalse(queue.schedule("launch", Paths.get("C:/resources"),
                    Paths.get("C:/classes"), Paths.get("C:/resources/mapper.xml")));
            assertEquals(0, queue.getPendingCount());
        } finally {
            queue.close();
        }
    }

    @Test void keepsIndependentOutputRootsWhenOneSourceFileFeedsTwoDebugSessions()
            throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        CountDownLatch fired = new CountDownLatch(2);
        java.util.Set<String> launches = java.util.Collections.synchronizedSet(
                new java.util.HashSet<String>());
        MapperReloadQueue queue = new MapperReloadQueue(scheduler, 10, 8,
                (launchId, root, output, file) -> {
                    launches.add(launchId);
                    fired.countDown();
                });
        try {
            Path source = Paths.get("C:/shared-resources/mapper.xml");
            queue.schedule("launch-a", Paths.get("C:/shared-resources"),
                    Paths.get("C:/classes-a"), source);
            queue.schedule("launch-b", Paths.get("C:/shared-resources"),
                    Paths.get("C:/classes-b"), source);

            assertTrue(fired.await(2, TimeUnit.SECONDS));
            assertEquals(new java.util.HashSet<String>(java.util.Arrays.asList(
                    "launch-a", "launch-b")), launches);
        } finally {
            queue.close();
            scheduler.shutdownNow();
        }
    }
}
