package com.xianyu2.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebSocketServiceImplTest {

    @Test
    void removingAccountCancelsEveryRuntimeTaskAndPreventsReconnect() {
        WebSocketServiceImpl service = new WebSocketServiceImpl();
        Long accountId = 3L;
        ScheduledFuture<?> heartbeatTask = mock(ScheduledFuture.class);
        ScheduledFuture<?> tokenRefreshTask = mock(ScheduledFuture.class);
        ScheduledFuture<?> tokenRetryTask = mock(ScheduledFuture.class);
        Future<?> reconnectTask = mock(Future.class);

        map(service, "heartbeatTasks").put(accountId, heartbeatTask);
        map(service, "tokenRefreshTasks").put(accountId, tokenRefreshTask);
        map(service, "tokenRetryTasks").put(accountId, tokenRetryTask);
        map(service, "reconnectTasks").put(accountId, reconnectTask);

        service.removeAccount(accountId);

        verify(heartbeatTask).cancel(false);
        verify(tokenRefreshTask).cancel(false);
        verify(tokenRetryTask).cancel(false);
        verify(reconnectTask).cancel(false);
        assertTrue(manuallyStoppedAccounts(service).contains(accountId));
        assertFalse(map(service, "heartbeatTasks").containsKey(accountId));
        assertFalse(map(service, "tokenRefreshTasks").containsKey(accountId));
        assertFalse(map(service, "tokenRetryTasks").containsKey(accountId));
        assertFalse(map(service, "reconnectTasks").containsKey(accountId));
        assertTrue(map(service, "connectionLocks").containsKey(accountId));
        assertFalse(service.ensureConnected(accountId));
    }

    @SuppressWarnings("unchecked")
    private <T> Map<Long, T> map(WebSocketServiceImpl service, String fieldName) {
        return (Map<Long, T>) ReflectionTestUtils.getField(service, fieldName);
    }

    @SuppressWarnings("unchecked")
    private Set<Long> manuallyStoppedAccounts(WebSocketServiceImpl service) {
        return (Set<Long>) ReflectionTestUtils.getField(service, "manuallyStoppedAccounts");
    }
}
