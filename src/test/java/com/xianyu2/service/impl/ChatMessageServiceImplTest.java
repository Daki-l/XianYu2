package com.xianyu2.service.impl;

import com.xianyu2.common.ResultObject;
import com.xianyu2.controller.dto.EndHumanTakeoverReqDTO;
import com.xianyu2.controller.dto.MsgContextReqDTO;
import com.xianyu2.controller.dto.MsgDTO;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.entity.XianyuChatMessage;
import com.xianyu2.entity.XianyuHumanInterventionRecord;
import com.xianyu2.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import com.xianyu2.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.xianyu2.mapper.XianyuHumanInterventionRecordMapper;
import com.xianyu2.service.ChatMessagePersistenceService;
import com.xianyu2.service.WebSocketService;
import com.xianyu2.service.reply.HumanTakeoverManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceImplTest {

    @Mock
    private XianyuChatMessageMapper messageMapper;
    @Mock
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    @Mock
    private XianyuHumanInterventionRecordMapper interventionRecordMapper;
    @Mock
    private ChatMessagePersistenceService persistenceService;
    @Mock
    private XianyuAccountMapper accountMapper;
    @Mock
    private WebSocketService webSocketService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private HumanTakeoverManager takeoverManager;

    private ChatMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatMessageServiceImpl();
        ReflectionTestUtils.setField(service, "chatMessageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "autoReplyRecordMapper", autoReplyRecordMapper);
        ReflectionTestUtils.setField(service, "interventionRecordMapper", interventionRecordMapper);
        ReflectionTestUtils.setField(service, "takeoverManager", takeoverManager);
        ReflectionTestUtils.setField(service, "chatMessagePersistenceService", persistenceService);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
        XianyuAccount account = new XianyuAccount();
        account.setUnb("own-user");
        when(accountMapper.selectById(1L)).thenReturn(account);
        lenient().when(autoReplyRecordMapper.findTimelineStates(1L, "sid@goofish")).thenReturn(List.of());
    }

    @Test
    void contextTimelineIsChronologicalWithIdAsTieBreaker() {
        when(messageMapper.findRecentBySId(1L, "sid@goofish", 20, 0)).thenReturn(List.of(
                message(9L, 2_000L),
                message(7L, 2_000L),
                message(3L, 1_000L)));
        MsgContextReqDTO request = new MsgContextReqDTO();
        request.setXianyuAccountId(1L);
        request.setSid("sid@goofish");
        request.setLimit(20);
        request.setOffset(0);

        ResultObject<?> response = service.getContextMessages(request);

        @SuppressWarnings("unchecked")
        List<MsgDTO> timeline = (List<MsgDTO>) response.getData();
        assertEquals(List.of(3L, 7L, 9L), timeline.stream().map(MsgDTO::getId).toList());
    }

    @Test
    void activeHumanTakeoverIsExposedAsVirtualTimelineItem() {
        XianyuHumanInterventionRecord takeover = new XianyuHumanInterventionRecord();
        takeover.setId(12L);
        takeover.setSId("sid@goofish");
        takeover.setXyGoodsId("goods-1");
        takeover.setCreatedTime(LocalDateTime.of(2026, 10, 9, 12, 0));
        takeover.setEndTime(LocalDateTime.of(2026, 10, 9, 12, 10));
        when(interventionRecordMapper.findActiveByAccountAndSId(1L, "sid@goofish")).thenReturn(takeover);

        MsgContextReqDTO request = new MsgContextReqDTO();
        request.setXianyuAccountId(1L);
        request.setSid("sid@goofish");
        request.setOffset(0);

        ResultObject<?> response = service.getContextMessages(request);

        @SuppressWarnings("unchecked")
        List<MsgDTO> timeline = (List<MsgDTO>) response.getData();
        MsgDTO status = timeline.stream()
                .filter(message -> "HUMAN_TAKEOVER".equals(message.getTimelineType()))
                .findFirst()
                .orElseThrow();
        assertEquals("sid@goofish", status.getSId());
        assertEquals("goods-1", status.getXyGoodsId());
        assertEquals(takeover.getEndTime(), status.getTakeoverEndTime());
        assertNotNull(status.getMessageTime());
    }

    @Test
    void forceEndingHumanTakeoverDoesNotCreateOrSendAnAutoReply() {
        EndHumanTakeoverReqDTO request = new EndHumanTakeoverReqDTO();
        request.setXianyuAccountId(1L);
        request.setSid("sid@goofish");
        when(takeoverManager.endTakeover(1L, "sid@goofish")).thenReturn(false);

        ResultObject<?> response = service.endHumanTakeover(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals(true, data.get("ended"));
        assertEquals(false, data.get("changed"));
        assertEquals("人工接管已强制结束", data.get("message"));
        verify(takeoverManager).endTakeover(1L, "sid@goofish");
        verifyNoInteractions(autoReplyRecordMapper, persistenceService, webSocketService, eventPublisher);
    }

    @Test
    void historySyncPublishesAnEventForANewPlatformMessage() {
        long createAt = 1_788_953_954_750L;
        when(webSocketService.listConversationHistory(1L, "sid@goofish", 500))
                .thenReturn(List.of(historyMessage(createAt)));
        when(persistenceService.savePlatformHistory(org.mockito.ArgumentMatchers.any(), eq("own-user"))).thenReturn(1);

        MsgContextReqDTO request = new MsgContextReqDTO();
        request.setXianyuAccountId(1L);
        request.setSid("sid@goofish");
        request.setMaxMessages(500);

        service.syncContextMessages(request);

        org.mockito.ArgumentCaptor<XianyuChatMessage> captured =
                org.mockito.ArgumentCaptor.forClass(XianyuChatMessage.class);
        verify(persistenceService).savePlatformHistory(captured.capture(), eq("own-user"));
        assertEquals("history-message-1", captured.getValue().getPnmId());
        assertEquals(createAt, captured.getValue().getMessageTime());
        org.mockito.ArgumentCaptor<ChatMessageReceivedEvent> event =
                org.mockito.ArgumentCaptor.forClass(ChatMessageReceivedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals("history-message-1", event.getValue().getMessageData().getPnmId());
        assertEquals(createAt, event.getValue().getMessageData().getMessageTime());
    }

    @Test
    void historySyncDoesNotRepublishAnExistingPlatformMessage() {
        when(webSocketService.listConversationHistory(1L, "sid@goofish", 500))
                .thenReturn(List.of(historyMessage(1_788_953_954_750L)));
        when(persistenceService.savePlatformHistory(org.mockito.ArgumentMatchers.any(), eq("own-user"))).thenReturn(0);

        MsgContextReqDTO request = new MsgContextReqDTO();
        request.setXianyuAccountId(1L);
        request.setSid("sid@goofish");
        request.setMaxMessages(500);

        service.syncContextMessages(request);

        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    private XianyuChatMessage message(Long id, Long messageTime) {
        XianyuChatMessage message = new XianyuChatMessage();
        message.setId(id);
        message.setSId("sid@goofish");
        message.setContentType(1);
        message.setMsgContent("message-" + id);
        message.setMessageTime(messageTime);
        return message;
    }

    private Map<String, Object> historyMessage(long createAt) {
        String content = "{\"contentType\":1,\"text\":{\"text\":\"corrected content\"}}";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        return Map.of("message", Map.of(
                "messageId", "history-message-1",
                "cid", "sid@goofish",
                "createAt", createAt,
                "extension", Map.of("senderUserId", "own-user"),
                "content", Map.of("custom", Map.of("data", encodedContent))));
    }
}
