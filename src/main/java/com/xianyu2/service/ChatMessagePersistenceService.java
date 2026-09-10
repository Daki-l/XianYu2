package com.xianyu2.service;

import com.xianyu2.entity.XianyuChatMessage;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 统一保存聊天消息，并协调平台回流消息与本地发送记录的跨来源去重。
 */
@Slf4j
@Service
public class ChatMessagePersistenceService {

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
        }
    }

    private boolean requiresCrossSourceReconciliation(XianyuChatMessage message) {
        return message.getContentType() != null
                && (message.getContentType() == ChatMessageDeduplication.PLATFORM_CONTENT_TYPE
                || message.getContentType() == ChatMessageDeduplication.LOCAL_AI_CONTENT_TYPE
                || message.getContentType() == ChatMessageDeduplication.LOCAL_MANUAL_REPLY_CONTENT_TYPE);
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
            reconcileWithCandidates(message, ownUserId, ChatMessageDeduplication.LOCAL_AI_SOURCE,
                    ChatMessageDeduplication.LOCAL_AI_CONTENT_TYPE, true, false);
            reconcileWithCandidates(message, ownUserId, ChatMessageDeduplication.LOCAL_SOURCE,
                    ChatMessageDeduplication.LOCAL_MANUAL_REPLY_CONTENT_TYPE, false, true);
            return;
        }

        reconcileWithCandidates(message, ownUserId, ChatMessageDeduplication.PLATFORM_SOURCE,
                ChatMessageDeduplication.PLATFORM_CONTENT_TYPE, localAiMessage, localManualMessage);
    }

    private void reconcileWithCandidates(XianyuChatMessage message, String ownUserId,
                                         String candidateSource, int candidateContentType,
                                         boolean markAiReplyOrigin, boolean requireOwnSender) {
        List<XianyuChatMessage> candidates = messageMapper.findCrossSourceCandidates(
                message.getXianyuAccountId(), message.getSId(), null,
                candidateSource, candidateContentType);
        List<XianyuChatMessage> matches = candidates.stream()
                .filter(candidate -> isMatch(message, candidate, ownUserId, requireOwnSender))
                .toList();
        if (matches.size() != 1) {
            return;
        }

        XianyuChatMessage candidate = matches.get(0);
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
        messageMapper.markDuplicate(localId, platformId);
        if (markAiReplyOrigin) {
            messageMapper.markAiReplyOrigin(platformId);
        }
        log.info("跨来源消息去重: accountId={}, sid={}, platformId={}, localId={}, aiReply={}",
                message.getXianyuAccountId(), message.getSId(), platformId, localId, markAiReplyOrigin);
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
