package com.xianyu2.service;

import com.xianyu2.entity.XianyuChatMessage;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessagePersistenceServiceTest {

    @Mock
    private XianyuChatMessageMapper messageMapper;

    @Mock
    private AccountService accountService;

    private ChatMessagePersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new ChatMessagePersistenceService(messageMapper, accountService);
        lenient().when(accountService.getXianyuUserId(1L)).thenReturn("own-user");
        lenient().when(messageMapper.insert(any())).thenAnswer(invocation -> {
            XianyuChatMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(100L);
            }
            return 1;
        });
        lenient().when(messageMapper.upsertPlatformHistory(any())).thenAnswer(invocation -> {
            XianyuChatMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(100L);
            }
            return 1;
        });
        lenient().when(messageMapper.markDuplicate(any(), any())).thenReturn(1);
    }

    @Test
    void platformMessageWinsWhenThereIsOneMatchingLocalAiMessage() {
        XianyuChatMessage local = message(200L, 888, "own-user", 1_000L);
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888))
                .thenReturn(List.of(local));

        XianyuChatMessage platform = message(null, 1, "own-user", 2_000L);
        persistenceService.save(platform);

        verify(messageMapper).markDuplicate(200L, 100L);
        verify(messageMapper).markReplyOrigin(100L, "AI");
        assertEquals("PLATFORM", platform.getMessageSource());
        assertEquals("AI", platform.getReplyOrigin());
        assertTrue(platform.getDedupeFingerprint() != null);
    }

    @Test
    void platformHistoryUsesDedicatedUpsertAndStillReconcilesDuplicates() {
        XianyuChatMessage local = message(200L, 888, "own-user", 1_000L);
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888))
                .thenReturn(List.of(local));

        XianyuChatMessage platform = message(null, 1, "own-user", 2_000L);
        int result = persistenceService.savePlatformHistory(platform, "own-user");

        verify(messageMapper).insertPlatformHistoryIfAbsent(platform);
        verify(messageMapper).upsertPlatformHistory(platform);
        verify(messageMapper, never()).insert(platform);
        verify(messageMapper).markDuplicate(200L, 100L);
        verify(messageMapper).markReplyOrigin(100L, "AI");
        assertEquals(0, result);
    }

    @Test
    void newPlatformHistoryMessageIsReportedAsNewWithoutAnUpsert() {
        XianyuChatMessage platform = message(null, 1, "buyer-user", 2_000L);
        when(messageMapper.insertPlatformHistoryIfAbsent(platform)).thenAnswer(invocation -> {
            platform.setId(101L);
            return 1;
        });

        int result = persistenceService.savePlatformHistory(platform, "own-user");

        assertEquals(1, result);
        verify(messageMapper).insertPlatformHistoryIfAbsent(platform);
        verify(messageMapper, never()).upsertPlatformHistory(platform);
    }

    @Test
    void ambiguousCandidatesAreKept() {
        XianyuChatMessage first = message(200L, 888, "own-user", 1_000L);
        XianyuChatMessage second = message(201L, 888, "own-user", 1_000L);
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888))
                .thenReturn(List.of(first, second));

        persistenceService.save(message(null, 1, "own-user", 2_000L));

        verify(messageMapper, never()).markDuplicate(any(), any());
        verify(messageMapper, never()).markReplyOrigin(any(), any());
    }

    @Test
    void buyerPlatformMessageIsNotMatchedWithLocalAiMessage() {
        persistenceService.save(message(null, 1, "buyer-user", 2_000L));

        verify(messageMapper, never()).findCrossSourceCandidates(
                any(), any(), any(), any(), any());
        verify(messageMapper, never()).markDuplicate(any(), any());
    }

    @Test
    void localAiMessageIsKeptWhenPlatformMessageIsAbsent() {
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "PLATFORM", 1))
                .thenReturn(List.of());

        XianyuChatMessage local = message(null, 888, "own-user", 2_000L);
        persistenceService.save(local);

        verify(messageMapper, never()).markDuplicate(any(), any());
        assertEquals("LOCAL_AI", local.getMessageSource());
        assertEquals("AI", local.getReplyOrigin());
    }

    @Test
    void localAiMessageIsMarkedWhenPlatformMessageWasSavedFirst() {
        XianyuChatMessage platform = message(300L, 1, "own-user", 2_000L);
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "PLATFORM", 1))
                .thenReturn(List.of(platform));

        XianyuChatMessage local = message(null, 888, "own-user", 1_000L);
        persistenceService.save(local);

        verify(messageMapper).markDuplicate(100L, 300L);
        verify(messageMapper).markReplyOrigin(300L, "AI");
    }

    @Test
    void platformMessageWinsWhenThereIsOneMatchingLocalManualReply() {
        XianyuChatMessage localManual = message(200L, 999, "own-user", 1_000L);
        localManual.setMessageSource("LOCAL");
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888))
                .thenReturn(List.of());
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL", 999))
                .thenReturn(List.of(localManual));

        XianyuChatMessage platform = message(null, 1, "own-user", 2_000L);
        persistenceService.save(platform);

        verify(messageMapper).markDuplicate(200L, 100L);
        verify(messageMapper).markReplyOrigin(100L, "BACKEND");
        assertEquals("BACKEND", platform.getReplyOrigin());
    }

    @Test
    void platformMessageDoesNotMergeEquallyCloseAiAndManualCandidates() {
        XianyuChatMessage ai = message(200L, 888, "own-user", 1_000L);
        XianyuChatMessage manual = message(201L, 999, "own-user", 1_000L);
        manual.setMessageSource("LOCAL");
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888)).thenReturn(List.of(ai));
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL", 999)).thenReturn(List.of(manual));

        persistenceService.save(message(null, 1, "own-user", 2_000L));

        verify(messageMapper, never()).markDuplicate(any(), any());
        verify(messageMapper, never()).markReplyOrigin(any(), any());
    }

    @Test
    void platformImageWinsWhenThereIsOneMatchingLocalAiImage() {
        XianyuChatMessage localImage = message(200L, 887, "own-user", 1_000L);
        localImage.setMsgContent("[图片]http://example.test/image.png");
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 887)).thenReturn(List.of(localImage));
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL", 997)).thenReturn(List.of());

        XianyuChatMessage platformImage = message(null, 2, "own-user", 2_000L);
        platformImage.setMsgContent("https://example.test/image.png");
        persistenceService.save(platformImage);

        verify(messageMapper).markDuplicate(200L, 100L);
        verify(messageMapper).markReplyOrigin(100L, "AI");
    }

    @Test
    void localManualReplyIsMarkedWhenPlatformMessageWasSavedFirst() {
        XianyuChatMessage platform = message(300L, 1, "own-user", 2_000L);
        platform.setMessageSource("PLATFORM");
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "PLATFORM", 1))
                .thenReturn(List.of(platform));

        XianyuChatMessage localManual = message(null, 999, "own-user", 1_000L);
        persistenceService.save(localManual);

        verify(messageMapper).markDuplicate(100L, 300L);
        verify(messageMapper).markReplyOrigin(300L, "BACKEND");
    }

    @Test
    void manualReplyDoesNotMatchWhenThePlatformMessageWasSentByAnotherUser() {
        XianyuChatMessage platform = message(300L, 1, "buyer-user", 2_000L);
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "PLATFORM", 1))
                .thenReturn(List.of(platform));

        persistenceService.save(message(null, 999, "own-user", 1_000L));

        verify(messageMapper, never()).markDuplicate(any(), any());
    }

    @Test
    void sessionReconciliationHidesMatchingManualReply() {
        XianyuChatMessage platform = message(300L, 1, "own-user", 2_000L);
        platform.setMessageSource("PLATFORM");
        XianyuChatMessage localManual = message(200L, 999, "own-user", 1_000L);
        localManual.setMessageSource("LOCAL");
        when(messageMapper.findSessionCrossSourceMessages(1L, "sid@goofish"))
                .thenReturn(List.of(platform, localManual));
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888))
                .thenReturn(List.of());
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL", 999))
                .thenReturn(List.of(localManual));

        persistenceService.reconcileSession(1L, "sid@goofish");

        verify(messageMapper).markDuplicate(200L, 300L);
        verify(messageMapper).markReplyOrigin(300L, "BACKEND");
    }

    @Test
    void whitespaceDifferencesAreNormalizedBeforeMatching() {
        XianyuChatMessage local = message(200L, 888, "own-user", 1_000L);
        local.setMsgContent("reply text");
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888))
                .thenReturn(List.of(local));

        XianyuChatMessage platform = message(null, 1, "own-user", 2_000L);
        persistenceService.save(platform);

        verify(messageMapper).markDuplicate(200L, 100L);
    }

    @Test
    void messagesOutsideTheTimeWindowAreNotMerged() {
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888))
                .thenReturn(List.of(message(200L, 888, "own-user", 2_000L)));

        XianyuChatMessage platform = message(null, 1, "own-user", 86_400_000L);
        persistenceService.save(platform);

        verify(messageMapper, never()).markDuplicate(any(), any());
    }

    private XianyuChatMessage message(Long id, int contentType, String sender, long time) {
        XianyuChatMessage message = new XianyuChatMessage();
        message.setId(id);
        message.setXianyuAccountId(1L);
        message.setSId("sid@goofish");
        message.setPnmId("pnm-" + (id == null ? contentType : id));
        message.setContentType(contentType);
        message.setMsgContent("reply  text");
        message.setSenderUserId(sender);
        message.setMessageTime(time);
        message.setCompleteMsg("{}");
        return message;
    }
}
