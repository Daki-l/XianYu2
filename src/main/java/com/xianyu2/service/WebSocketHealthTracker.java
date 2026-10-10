package com.xianyu2.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Retains runtime evidence for a WebSocket account without sending probe messages.
 */
@Component
public class WebSocketHealthTracker {

    private static final long REALTIME_SYNC_STALE_AFTER_MS = 15 * 60 * 1000L;

    private final ConcurrentMap<Long, AccountHealth> accounts = new ConcurrentHashMap<>();
    private final LongSupplier now;

    public WebSocketHealthTracker() {
        this(System::currentTimeMillis);
    }

    WebSocketHealthTracker(LongSupplier now) {
        this.now = now;
    }

    public void recordConnectionEstablished(Long accountId) {
        AccountHealth health = healthFor(accountId);
        health.connectionEstablishedAt = now.getAsLong();
        health.lastRealtimeSyncAt = null;
    }

    public void recordRealtimeSync(Long accountId) {
        healthFor(accountId).lastRealtimeSyncAt = now.getAsLong();
    }

    /**
     * Returns the start time of the currently tracked transport connection.
     * A missing value means the message cannot be proven to be real-time.
     */
    public Long getConnectionEstablishedAt(Long accountId) {
        AccountHealth health = accounts.get(accountId);
        return health == null ? null : health.connectionEstablishedAt;
    }

    public void recordSendResult(Long accountId, long attemptStartedAt, boolean success, String failureReason) {
        AccountHealth health = healthFor(accountId);
        long observedAt = now.getAsLong();
        if (success) {
            health.lastSendSuccessAt = observedAt;
            return;
        }

        // Preserve a router-recorded platform code for this same request instead of replacing it
        // with the generic failed confirmation result returned to the caller.
        if (health.lastSendFailureAt != null && health.lastSendFailureAt >= attemptStartedAt
                && health.lastSendFailureReason != null && health.lastSendFailureReason.startsWith("PLATFORM_")) {
            return;
        }
        health.lastSendFailureAt = observedAt;
        health.lastSendFailureReason = failureReason;
    }

    public void recordPlatformResponseFailure(Long accountId, int code) {
        AccountHealth health = healthFor(accountId);
        health.lastSendFailureAt = now.getAsLong();
        health.lastSendFailureReason = "PLATFORM_" + code;
    }

    public HealthSnapshot snapshot(Long accountId, boolean transportConnected) {
        AccountHealth health = accounts.get(accountId);
        if (health == null) {
            return new HealthSnapshot(
                    transportConnected,
                    transportConnected ? StreamState.UNVERIFIED : StreamState.OFFLINE,
                    transportConnected ? SendState.UNVERIFIED : SendState.UNAVAILABLE,
                    null, null, null, null, null);
        }

        long currentTime = now.getAsLong();
        StreamState streamState = resolveStreamState(health.lastRealtimeSyncAt, transportConnected, currentTime);
        SendState sendState = resolveSendState(health.lastSendSuccessAt, health.lastSendFailureAt, transportConnected);
        return new HealthSnapshot(
                transportConnected,
                streamState,
                sendState,
                health.connectionEstablishedAt,
                health.lastRealtimeSyncAt,
                health.lastSendSuccessAt,
                health.lastSendFailureAt,
                health.lastSendFailureReason);
    }

    private AccountHealth healthFor(Long accountId) {
        return accounts.computeIfAbsent(accountId, ignored -> new AccountHealth());
    }

    private StreamState resolveStreamState(Long lastRealtimeSyncAt, boolean transportConnected, long currentTime) {
        if (!transportConnected) {
            return StreamState.OFFLINE;
        }
        if (lastRealtimeSyncAt == null) {
            return StreamState.UNVERIFIED;
        }
        return currentTime - lastRealtimeSyncAt <= REALTIME_SYNC_STALE_AFTER_MS
                ? StreamState.ACTIVE : StreamState.STALE;
    }

    private SendState resolveSendState(Long lastSuccessAt, Long lastFailureAt, boolean transportConnected) {
        if (lastFailureAt != null && (lastSuccessAt == null || lastFailureAt >= lastSuccessAt)) {
            return SendState.FAILED;
        }
        if (!transportConnected) {
            return SendState.UNAVAILABLE;
        }
        return lastSuccessAt == null ? SendState.UNVERIFIED : SendState.CONFIRMED;
    }

    public enum StreamState {
        ACTIVE,
        UNVERIFIED,
        STALE,
        OFFLINE
    }

    public enum SendState {
        CONFIRMED,
        UNVERIFIED,
        FAILED,
        UNAVAILABLE
    }

    public record HealthSnapshot(
            boolean transportConnected,
            StreamState realtimeSyncState,
            SendState sendState,
            Long connectionEstablishedAt,
            Long lastRealtimeSyncAt,
            Long lastSendSuccessAt,
            Long lastSendFailureAt,
            String lastSendFailureReason) {
    }

    private static final class AccountHealth {
        private volatile Long connectionEstablishedAt;
        private volatile Long lastRealtimeSyncAt;
        private volatile Long lastSendSuccessAt;
        private volatile Long lastSendFailureAt;
        private volatile String lastSendFailureReason;
    }
}
