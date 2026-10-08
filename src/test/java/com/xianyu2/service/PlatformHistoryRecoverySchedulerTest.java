package com.xianyu2.service;

import com.xianyu2.common.ResultObject;
import com.xianyu2.controller.dto.MsgContextReqDTO;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformHistoryRecoverySchedulerTest {

    @Mock
    private XianyuAccountMapper accountMapper;
    @Mock
    private XianyuChatMessageMapper messageMapper;
    @Mock
    private ChatMessageService chatMessageService;
    @Mock
    private WebSocketService webSocketService;

    private PlatformHistoryRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        scheduler = new PlatformHistoryRecoveryScheduler(accountMapper, messageMapper, chatMessageService,
                webSocketService, directExecutor);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "accountCooldownMs", 180_000L);
        ReflectionTestUtils.setField(scheduler, "sessionCooldownMs", 900_000L);
        ReflectionTestUtils.setField(scheduler, "lookbackMs", 1_800_000L);
        ReflectionTestUtils.setField(scheduler, "maxSessionsPerAccount", 3);
        ReflectionTestUtils.setField(scheduler, "messagesPerSession", 50);
    }

    @Test
    void synchronizesOnlyConnectedAccountsAndHonorsAccountCooldown() {
        XianyuAccount account = account(1L);
        when(accountMapper.selectReconnectableAccounts()).thenReturn(List.of(account));
        when(webSocketService.isConnected(1L)).thenReturn(true);
        when(messageMapper.findRecentSessionIds(eq(1L), anyLong(), eq(3))).thenReturn(List.of("session@goofish"));
        when(chatMessageService.syncContextMessages(any())).thenReturn(ResultObject.success(null));

        scheduler.recoverRecentSessions();
        scheduler.recoverRecentSessions();

        ArgumentCaptor<MsgContextReqDTO> request = ArgumentCaptor.forClass(MsgContextReqDTO.class);
        verify(chatMessageService).syncContextMessages(request.capture());
        assertEquals(1L, request.getValue().getXianyuAccountId());
        assertEquals("session@goofish", request.getValue().getSid());
        assertEquals(50, request.getValue().getMaxMessages());
    }

    @Test
    void skipsDisconnectedAccountsWithoutQueryingTheirSessions() {
        XianyuAccount account = account(1L);
        when(accountMapper.selectReconnectableAccounts()).thenReturn(List.of(account));
        when(webSocketService.isConnected(1L)).thenReturn(false);

        scheduler.recoverRecentSessions();

        verify(messageMapper, never()).findRecentSessionIds(anyLong(), anyLong(), eq(3));
        verify(chatMessageService, never()).syncContextMessages(any());
    }

    @Test
    void releasesTheSessionWhenTheExecutorRejectsTheRecoveryTask() {
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("executor saturated");
        };
        scheduler = new PlatformHistoryRecoveryScheduler(accountMapper, messageMapper, chatMessageService,
                webSocketService, rejectingExecutor);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "accountCooldownMs", 180_000L);
        ReflectionTestUtils.setField(scheduler, "sessionCooldownMs", 900_000L);
        ReflectionTestUtils.setField(scheduler, "lookbackMs", 1_800_000L);
        ReflectionTestUtils.setField(scheduler, "maxSessionsPerAccount", 3);
        ReflectionTestUtils.setField(scheduler, "messagesPerSession", 50);
        XianyuAccount account = account(1L);
        when(accountMapper.selectReconnectableAccounts()).thenReturn(List.of(account));
        when(webSocketService.isConnected(1L)).thenReturn(true);
        when(messageMapper.findRecentSessionIds(eq(1L), anyLong(), eq(3))).thenReturn(List.of("session@goofish"));

        scheduler.recoverRecentSessions();

        @SuppressWarnings("unchecked")
        Set<String> syncing = (Set<String>) ReflectionTestUtils.getField(scheduler, "syncingSessions");
        @SuppressWarnings("unchecked")
        Map<Long, Long> accountCooldowns = (Map<Long, Long>) ReflectionTestUtils.getField(scheduler, "accountLastSyncAt");
        assertTrue(syncing.isEmpty());
        assertTrue(accountCooldowns.isEmpty());
    }

    private XianyuAccount account(Long id) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id);
        account.setTenantId(10L);
        return account;
    }
}
