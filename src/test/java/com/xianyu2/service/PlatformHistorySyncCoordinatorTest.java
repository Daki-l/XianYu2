package com.xianyu2.service;

import com.xianyu2.common.ResultObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformHistorySyncCoordinatorTest {

    @Test
    void concurrentRequestsForTheSameSessionExecuteOnlyOnce() throws Exception {
        PlatformHistorySyncCoordinator coordinator = new PlatformHistorySyncCoordinator();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch allowLeaderToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ResultObject<?>> leader = executor.submit(() -> coordinator.execute(1L, "sid@goofish", () -> {
                executions.incrementAndGet();
                leaderStarted.countDown();
                try {
                    assertTrue(allowLeaderToFinish.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return ResultObject.success("synced");
            }));

            assertTrue(leaderStarted.await(5, TimeUnit.SECONDS));
            Future<ResultObject<?>> follower = executor.submit(() -> coordinator.execute(1L, "sid", () -> {
                executions.incrementAndGet();
                return ResultObject.success("duplicate");
            }));

            assertEquals(409, follower.get(5, TimeUnit.SECONDS).getCode());
            assertEquals(1, executions.get());

            allowLeaderToFinish.countDown();

            assertEquals(200, leader.get(5, TimeUnit.SECONDS).getCode());
            assertEquals(1, executions.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
