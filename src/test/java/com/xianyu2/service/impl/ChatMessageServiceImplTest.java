package com.xianyu2.service.impl;

import com.xianyu2.common.ResultObject;
import com.xianyu2.controller.dto.MsgContextReqDTO;
import com.xianyu2.controller.dto.MsgDTO;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.entity.XianyuChatMessage;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import com.xianyu2.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.xianyu2.service.ChatMessagePersistenceService;
import com.xianyu2.service.WebSocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceImplTest {

    @Mock
    private XianyuChatMessageMapper messageMapper;
    @Mock
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    @Mock
    private ChatMessagePersistenceService persistenceService;
    @Mock
    private XianyuAccountMapper accountMapper;
    @Mock
    private WebSocketService webSocketService;

    private ChatMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatMessageServiceImpl();
        ReflectionTestUtils.setField(service, "chatMessageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "autoReplyRecordMapper", autoReplyRecordMapper);
        ReflectionTestUtils.setField(service, "chatMessagePersistenceService", persistenceService);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
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
    void historySyncUsesTheDedicatedUpsertWithThePlatformTimestamp() {
        long createAt = 1_788_953_954_750L;
        when(webSocketService.listConversationHistory(1L, "sid@goofish", 500))
                .thenReturn(List.of(historyMessage(createAt)));

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
