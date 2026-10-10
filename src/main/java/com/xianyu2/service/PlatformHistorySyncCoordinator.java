package com.xianyu2.service;

import com.xianyu2.common.ResultObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Guards platform-history pulls for one account and conversation.
 *
 * <p>The API and the scheduled recovery job both call {@link ChatMessageService}, so this
 * singleton prevents either caller from opening a second WebSocket history request while the
 * first one is still parsing and persisting the same conversation. Duplicate callers receive
 * an immediate in-progress result instead of occupying a request thread until the leader ends.</p>
 */
@Slf4j
@Component
public class PlatformHistorySyncCoordinator {

    private final Set<SessionKey> syncingSessions = ConcurrentHashMap.newKeySet();

    public ResultObject<?> execute(Long accountId, String sid, Supplier<ResultObject<?>> syncOperation) {
        Objects.requireNonNull(accountId, "accountId不能为空");
        Objects.requireNonNull(syncOperation, "syncOperation不能为空");

        SessionKey key = new SessionKey(accountId, normalizeSid(sid));
        if (!syncingSessions.add(key)) {
            log.info("【账号{}】拒绝重复会话历史同步: sid={}", accountId, sid);
            return ResultObject.failed(409, "会话历史同步进行中，请稍后刷新");
        }

        try {
            return syncOperation.get();
        } finally {
            syncingSessions.remove(key);
        }
    }

    private String normalizeSid(String sid) {
        return sid == null ? "" : sid.replace("@goofish", "").trim();
    }

    private record SessionKey(Long accountId, String sid) {
    }
}
