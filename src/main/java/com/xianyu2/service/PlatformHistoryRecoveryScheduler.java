package com.xianyu2.service;

import com.xianyu2.common.ResultObject;
import com.xianyu2.context.TenantContext;
import com.xianyu2.controller.dto.MsgContextReqDTO;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 在实时 WebSocket 未推送消息时，低频补拉近期已知会话，保证新落库的买家消息仍能进入自动回复链路。
 */
@Slf4j
@Component
public class PlatformHistoryRecoveryScheduler {

    private final XianyuAccountMapper accountMapper;
    private final XianyuChatMessageMapper messageMapper;
    private final ChatMessageService chatMessageService;
    private final WebSocketService webSocketService;
    private final Executor taskExecutor;
    private final Map<Long, Long> accountLastSyncAt = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionLastSyncAt = new ConcurrentHashMap<>();
    private final Set<String> syncingSessions = ConcurrentHashMap.newKeySet();

    @Value("${app.message-history-recovery.enabled:true}")
    private boolean enabled;

    @Value("${app.message-history-recovery.account-cooldown-ms:180000}")
    private long accountCooldownMs;

    @Value("${app.message-history-recovery.session-cooldown-ms:900000}")
    private long sessionCooldownMs;

    @Value("${app.message-history-recovery.lookback-ms:1800000}")
    private long lookbackMs;

    @Value("${app.message-history-recovery.max-sessions-per-account:3}")
    private int maxSessionsPerAccount;

    @Value("${app.message-history-recovery.messages-per-session:50}")
    private int messagesPerSession;

    public PlatformHistoryRecoveryScheduler(XianyuAccountMapper accountMapper,
                                            XianyuChatMessageMapper messageMapper,
                                            ChatMessageService chatMessageService,
                                            WebSocketService webSocketService,
                                            @Qualifier("taskExecutor") Executor taskExecutor) {
        this.accountMapper = accountMapper;
        this.messageMapper = messageMapper;
        this.chatMessageService = chatMessageService;
        this.webSocketService = webSocketService;
        this.taskExecutor = taskExecutor;
    }

    @Scheduled(fixedDelayString = "${app.message-history-recovery.interval-ms:180000}", initialDelay = 30000)
    public void recoverRecentSessions() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        long since = now - lookbackMs;
        for (XianyuAccount account : accountMapper.selectReconnectableAccounts()) {
            Long accountId = account.getId();
            if (accountId == null || !webSocketService.isConnected(accountId) || !canSyncAccount(accountId, now)) {
                continue;
            }
            List<String> sessionIds = messageMapper.findRecentSessionIds(accountId, since, maxSessionsPerAccount);
            for (String sessionId : sessionIds) {
                if (canSyncSession(accountId, sessionId, now)) {
                    accountLastSyncAt.put(accountId, now);
                    try {
                        taskExecutor.execute(() -> syncSession(account, sessionId));
                    } catch (RuntimeException e) {
                        syncingSessions.remove(sessionKey(accountId, sessionId));
                        accountLastSyncAt.remove(accountId, now);
                        log.warn("【账号{}】会话历史补拉任务未提交: sid={}, error={}",
                                accountId, sessionId, e.getMessage());
                    }
                    break;
                }
            }
        }
    }

    private boolean canSyncAccount(Long accountId, long now) {
        Long lastSync = accountLastSyncAt.get(accountId);
        return lastSync == null || now - lastSync >= accountCooldownMs;
    }

    private boolean canSyncSession(Long accountId, String sessionId, long now) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        String key = sessionKey(accountId, sessionId);
        Long lastSync = sessionLastSyncAt.get(key);
        return (lastSync == null || now - lastSync >= sessionCooldownMs) && syncingSessions.add(key);
    }

    private void syncSession(XianyuAccount account, String sessionId) {
        Long accountId = account.getId();
        String key = sessionKey(accountId, sessionId);
        try {
            TenantContext.set(account.getTenantId());
            MsgContextReqDTO request = new MsgContextReqDTO();
            request.setXianyuAccountId(accountId);
            request.setSid(sessionId);
            request.setMaxMessages(messagesPerSession);
            ResultObject<?> result = chatMessageService.syncContextMessages(request);
            if (result.getCode() == null || result.getCode() != 200) {
                log.warn("【账号{}】会话历史补拉未完成: sid={}, message={}", accountId, sessionId, result.getMsg());
            }
        } catch (Exception e) {
            log.warn("【账号{}】会话历史补拉失败: sid={}, error={}", accountId, sessionId, e.getMessage());
        } finally {
            sessionLastSyncAt.put(key, System.currentTimeMillis());
            syncingSessions.remove(key);
            TenantContext.clear();
        }
    }

    private String sessionKey(Long accountId, String sessionId) {
        return accountId + ":" + sessionId;
    }
}
