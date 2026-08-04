package dev.hotreload.agent.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BoundedMutationExecutorTest {
    @Test void permitsOneRunningAndEightQueuedMutations() throws Exception {
        BoundedMutationExecutor executor = new BoundedMutationExecutor(8);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(executor.submit(() -> {
                started.countDown();
                await(release);
            }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 8; i++) assertTrue(executor.submit(() -> { }));
            assertFalse(executor.submit(() -> { }));
            assertEquals(8, executor.getQueueSize());
        } finally {
            release.countDown();
            executor.close();
        }
        assertTrue(executor.isTerminated());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
