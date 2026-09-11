package com.xianyu2.service;

import com.xianyu2.entity.XianyuChatMessage;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一保存聊天消息，并协调平台回流消息与本地发送记录的跨来源去重。
 */
@Slf4j
@Service
public class ChatMessagePersistenceService {

    private static final long CROSS_SOURCE_MATCH_WINDOW_MILLIS = 5 * 60 * 1_000L;

    private final XianyuChatMessageMapper messageMapper;
    private final AccountService accountService;

    public ChatMessagePersistenceService(XianyuChatMessageMapper messageMapper,
                                         AccountService accountService) {
        this.messageMapper = messageMapper;
        this.accountService = accountService;
    }

    @Transactional
    public int save(XianyuChatMessage message) {
        String ownUserId = requiresCrossSourceReconciliation(message)
                ? accountService.getXianyuUserId(message.getXianyuAccountId())
                : null;
        return save(message, ownUserId);
    }

    @Transactional
    public int save(XianyuChatMessage message, String ownUserId) {
        prepareMetadata(message);
        int result = messageMapper.insert(message);
        reconcileCrossSourceDuplicate(message, ownUserId);
        return result;
    }

    /**
     * 同步平台完整历史时，以平台消息的事件时间和原始载荷纠正已存在记录。
     */
    @Transactional
    public int savePlatformHistory(XianyuChatMessage message, String ownUserId) {
        prepareMetadata(message);
        int result = messageMapper.upsertPlatformHistory(message);
        reconcileCrossSourceDuplicate(message, ownUserId);
        return result;
    }

    /**
     * 修复已存在的会话重复记录。只会标记唯一明确匹配的记录。
     */
    @Transactional
    public void reconcileSession(Long accountId, String sid) {
        if (accountId == null || sid == null || sid.isBlank()) {
            return;
        }
        String ownUserId = accountService.getXianyuUserId(accountId);
        List<XianyuChatMessage> messages = messageMapper.findSessionCrossSourceMessages(accountId, sid);
        for (XianyuChatMessage message : messages) {
            if (ChatMessageDeduplication.isPlatformCandidate(message.getContentType(), message.getMessageSource())) {
                reconcileCrossSourceDuplicate(message, ownUserId);
            }
        }
    }

    private void prepareMetadata(XianyuChatMessage message) {
        if (message.getMessageSource() == null || message.getMessageSource().isBlank()) {
            message.setMessageSource(ChatMessageDeduplication.sourceForContentType(message.getContentType()));
        }
        if (message.getDedupeFingerprint() == null || message.getDedupeFingerprint().isBlank()) {
            message.setDedupeFingerprint(ChatMessageDeduplication.fingerprint(message.getMsgContent()));
        }
        if (ChatMessageDeduplication.isLocalAiCandidate(
                message.getContentType(), message.getMessageSource())) {
            message.setReplyOrigin("AI");
        } else if (ChatMessageDeduplication.isLocalManualReplyCandidate(
                message.getContentType(), message.getMessageSource())) {
            message.setReplyOrigin("BACKEND");
        }
    }

    private boolean requiresCrossSourceReconciliation(XianyuChatMessage message) {
        return ChatMessageDeduplication.supportsCrossSourceReconciliation(message.getContentType());
    }

    private void reconcileCrossSourceDuplicate(XianyuChatMessage message, String ownUserId) {
        boolean platformMessage = ChatMessageDeduplication.isPlatformCandidate(
                message.getContentType(), message.getMessageSource());
        boolean localAiMessage = ChatMessageDeduplication.isLocalAiCandidate(
                message.getContentType(), message.getMessageSource());
        boolean localManualMessage = ChatMessageDeduplication.isLocalManualReplyCandidate(
                message.getContentType(), message.getMessageSource());
        if (!platformMessage && !localAiMessage && !localManualMessage) {
            return;
        }
        if (ownUserId == null || ownUserId.isBlank() || message.getSId() == null || message.getSId().isBlank()) {
            return;
        }
        if (message.getSenderUserId() != null && !message.getSenderUserId().isBlank()
                && !ownUserId.equals(message.getSenderUserId())) {
            return;
        }
        if (ChatMessageDeduplication.normalizeContent(message.getMsgContent()).isBlank()) {
            return;
        }

        if (platformMessage) {
            reconcilePlatformMessage(message, ownUserId);
            return;
        }

        Integer platformContentType = ChatMessageDeduplication.platformContentTypeForLocal(message.getContentType());
        if (platformContentType == null) {
            return;
        }
        reconcileWithCandidates(message, ownUserId, ChatMessageDeduplication.PLATFORM_SOURCE,
                platformContentType,
                localAiMessage ? "AI" : (localManualMessage ? "BACKEND" : null), localManualMessage);
    }

    private void reconcilePlatformMessage(XianyuChatMessage platformMessage, String ownUserId) {
        Integer aiContentType = ChatMessageDeduplication.localAiContentTypeForPlatform(platformMessage.getContentType());
        Integer manualContentType = ChatMessageDeduplication.localManualContentTypeForPlatform(platformMessage.getContentType());
        if (aiContentType == null || manualContentType == null) {
            return;
        }
        List<ReplyCandidate> candidates = new ArrayList<>();
        addPlatformCandidates(candidates, platformMessage, ownUserId, ChatMessageDeduplication.LOCAL_AI_SOURCE,
                aiContentType, "AI", false);
        addPlatformCandidates(candidates, platformMessage, ownUserId, ChatMessageDeduplication.LOCAL_SOURCE,
                manualContentType, "BACKEND", true);
        ReplyCandidate candidate = nearestReplyCandidate(platformMessage, candidates);
        if (candidate != null) {
            reconcilePair(platformMessage, candidate.message(), candidate.replyOrigin());
        }
    }

    private void addPlatformCandidates(List<ReplyCandidate> matches, XianyuChatMessage platformMessage,
                                       String ownUserId, String source, int contentType,
                                       String replyOrigin, boolean requireOwnSender) {
        List<XianyuChatMessage> candidates = messageMapper.findCrossSourceCandidates(
                platformMessage.getXianyuAccountId(), platformMessage.getSId(), null, source, contentType);
        if (candidates == null) {
            return;
        }
        candidates.stream()
                .filter(candidate -> isMatch(platformMessage, candidate, ownUserId, requireOwnSender))
                .map(candidate -> new ReplyCandidate(candidate, replyOrigin))
                .forEach(matches::add);
    }

    private void reconcileWithCandidates(XianyuChatMessage message, String ownUserId,
                                         String candidateSource, int candidateContentType,
                                         String replyOrigin, boolean requireOwnSender) {
        List<XianyuChatMessage> candidates = messageMapper.findCrossSourceCandidates(
                message.getXianyuAccountId(), message.getSId(), null,
                candidateSource, candidateContentType);
        List<XianyuChatMessage> matches = (candidates == null ? List.<XianyuChatMessage>of() : candidates).stream()
                .filter(candidate -> isMatch(message, candidate, ownUserId, requireOwnSender))
                .toList();
        XianyuChatMessage candidate = nearestCandidate(message, matches);
        if (candidate == null) {
            return;
        }
        reconcilePair(message, candidate, replyOrigin);
    }

    private void reconcilePair(XianyuChatMessage message, XianyuChatMessage candidate, String replyOrigin) {
        Long currentId = message.getId();
        if (currentId == null) {
            XianyuChatMessage persisted = messageMapper.findByPnmId(
                    message.getXianyuAccountId(), message.getPnmId());
            currentId = persisted == null ? null : persisted.getId();
        }
        if (currentId == null || candidate.getId() == null) {
            return;
        }

        Long platformId;
        Long localId;
        if (ChatMessageDeduplication.isPlatformCandidate(message.getContentType(), message.getMessageSource())) {
            platformId = currentId;
            localId = candidate.getId();
        } else {
            platformId = candidate.getId();
            localId = currentId;
        }
        if (platformId.equals(localId)) {
            return;
        }
        if (messageMapper.markDuplicate(localId, platformId) != 1) {
            return;
        }
        if (replyOrigin != null) {
            messageMapper.markReplyOrigin(platformId, replyOrigin);
        }
        log.info("跨来源消息去重: accountId={}, sid={}, platformId={}, localId={}, aiReply={}",
                message.getXianyuAccountId(), message.getSId(), platformId, localId, "AI".equals(replyOrigin));
    }

    private XianyuChatMessage nearestCandidate(XianyuChatMessage incoming, List<XianyuChatMessage> candidates) {
        if (incoming.getMessageTime() == null) {
            return null;
        }
        XianyuChatMessage nearest = null;
        long nearestDelta = Long.MAX_VALUE;
        boolean ambiguous = false;
        for (XianyuChatMessage candidate : candidates) {
            if (candidate.getMessageTime() == null) {
                continue;
            }
            long delta = Math.abs(incoming.getMessageTime() - candidate.getMessageTime());
            if (delta > CROSS_SOURCE_MATCH_WINDOW_MILLIS) {
                continue;
            }
            if (delta < nearestDelta) {
                nearest = candidate;
                nearestDelta = delta;
                ambiguous = false;
            } else if (delta == nearestDelta) {
                ambiguous = true;
            }
        }
        return ambiguous ? null : nearest;
    }

    private ReplyCandidate nearestReplyCandidate(XianyuChatMessage incoming, List<ReplyCandidate> candidates) {
        if (incoming.getMessageTime() == null) {
            return null;
        }
        ReplyCandidate nearest = null;
        long nearestDelta = Long.MAX_VALUE;
        boolean ambiguous = false;
        for (ReplyCandidate candidate : candidates) {
            Long candidateTime = candidate.message().getMessageTime();
            if (candidateTime == null) {
                continue;
            }
            long delta = Math.abs(incoming.getMessageTime() - candidateTime);
            if (delta > CROSS_SOURCE_MATCH_WINDOW_MILLIS) {
                continue;
            }
            if (delta < nearestDelta) {
                nearest = candidate;
                nearestDelta = delta;
                ambiguous = false;
            } else if (delta == nearestDelta) {
                ambiguous = true;
            }
        }
        return ambiguous ? null : nearest;
    }

    private record ReplyCandidate(XianyuChatMessage message, String replyOrigin) {
    }

    private boolean isMatch(XianyuChatMessage incoming, XianyuChatMessage candidate, String ownUserId,
                            boolean requireOwnSender) {
        String incomingFingerprint = incoming.getDedupeFingerprint();
        if (incomingFingerprint == null || incomingFingerprint.isBlank()) {
            incomingFingerprint = ChatMessageDeduplication.fingerprint(incoming.getMsgContent());
        }
        String candidateFingerprint = candidate.getDedupeFingerprint();
        if (candidateFingerprint == null || candidateFingerprint.isBlank()) {
            candidateFingerprint = ChatMessageDeduplication.fingerprint(candidate.getMsgContent());
        }
        if (!incomingFingerprint.equals(candidateFingerprint)) {
            return false;
        }
        String candidateSender = candidate.getSenderUserId();
        if (requireOwnSender) {
            return ownUserId.equals(incoming.getSenderUserId()) && ownUserId.equals(candidateSender);
        }
        return candidateSender == null || candidateSender.isBlank() || ownUserId.equals(candidateSender);
    }
}
