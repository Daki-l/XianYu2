package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuChatMessage;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        when(accountService.getXianyuUserId(1L)).thenReturn("own-user");
        when(messageMapper.insert(any())).thenAnswer(invocation -> {
            XianyuChatMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(100L);
            }
            return 1;
        });
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
        verify(messageMapper).markAiReplyOrigin(100L);
        assertEquals("PLATFORM", platform.getMessageSource());
        assertTrue(platform.getDedupeFingerprint() != null);
    }

    @Test
    void ambiguousCandidatesAreKept() {
        XianyuChatMessage first = message(200L, 888, "own-user", 1_000L);
        XianyuChatMessage second = message(201L, 888, "own-user", 1_500L);
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888))
                .thenReturn(List.of(first, second));

        persistenceService.save(message(null, 1, "own-user", 2_000L));

        verify(messageMapper, never()).markDuplicate(any(), any());
        verify(messageMapper, never()).markAiReplyOrigin(any());
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
        verify(messageMapper).markAiReplyOrigin(300L);
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
    void messagesWithDifferentTimesStillMergeWhenTheOtherFieldsMatch() {
        when(messageMapper.findCrossSourceCandidates(
                1L, "sid@goofish", null, "LOCAL_AI", 888))
                .thenReturn(List.of(message(200L, 888, "own-user", 2_000L)));

        XianyuChatMessage platform = message(null, 1, "own-user", 86_400_000L);
        persistenceService.save(platform);

        verify(messageMapper).markDuplicate(200L, 100L);
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
