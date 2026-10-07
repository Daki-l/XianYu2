package com.xianyu2.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketHealthTrackerTest {

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private final WebSocketHealthTracker tracker = new WebSocketHealthTracker(now::get);

    @Test
    void connectedAccountIsUnverifiedUntilRealtimeSyncArrives() {
        tracker.recordConnectionEstablished(2L);

        WebSocketHealthTracker.HealthSnapshot snapshot = tracker.snapshot(2L, true);

        assertEquals(WebSocketHealthTracker.StreamState.UNVERIFIED, snapshot.realtimeSyncState());
        assertEquals(WebSocketHealthTracker.SendState.UNVERIFIED, snapshot.sendState());
    }

    @Test
    void platformFailureOverridesTransportOnlyConnectionStatus() {
        tracker.recordConnectionEstablished(2L);
        tracker.recordPlatformResponseFailure(2L, 500);

        WebSocketHealthTracker.HealthSnapshot snapshot = tracker.snapshot(2L, true);

        assertEquals(WebSocketHealthTracker.SendState.FAILED, snapshot.sendState());
        assertEquals("PLATFORM_500", snapshot.lastSendFailureReason());
    }

    @Test
    void newerConfirmedSendRestoresSendStatusWithoutHidingFailureHistory() {
        tracker.recordPlatformResponseFailure(2L, 500);
        now.addAndGet(1);
        tracker.recordSendResult(2L, now.get(), true, null);

        WebSocketHealthTracker.HealthSnapshot snapshot = tracker.snapshot(2L, true);

        assertEquals(WebSocketHealthTracker.SendState.CONFIRMED, snapshot.sendState());
        assertEquals("PLATFORM_500", snapshot.lastSendFailureReason());
    }
}
