package com.xianyu2.event.chatMessageEvent.lister;

import com.xianyu2.entity.XianyuChatMessage;
import com.xianyu2.event.chatMessageEvent.ChatMessageData;
import com.xianyu2.event.chatMessageEvent.ChatMessageEventSource;
import com.xianyu2.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import com.xianyu2.service.ChatMessagePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageEventSaveListenerTest {

    @Mock
    private XianyuChatMessageMapper messageMapper;

    @Mock
    private ChatMessagePersistenceService persistenceService;

    private ChatMessageEventSaveListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChatMessageEventSaveListener();
        ReflectionTestUtils.setField(listener, "chatMessageMapper", messageMapper);
        ReflectionTestUtils.setField(listener, "chatMessagePersistenceService", persistenceService);
    }

    @Test
    void duplicateMessageIsMarkedIneligibleBeforeOtherListenersRun() {
        XianyuChatMessage existing = persistedMessage(null);
        when(messageMapper.findByPnmId(1L, "message-1")).thenReturn(existing);
        ChatMessageReceivedEvent event = event();

        listener.handleChatMessageReceived(event);

        assertTrue(event.isPersistenceCompleted());
        assertFalse(event.isNewlyPersisted());
        assertFalse(event.isEligibleForRealtimeSideEffects());
        verify(persistenceService, never()).save(any());
    }

    @Test
    void localReplyEchoIsMarkedIneligibleAfterPersistenceReconciliation() {
        XianyuChatMessage persisted = persistedMessage("AI");
        when(messageMapper.findByPnmId(1L, "message-1")).thenReturn(null, persisted);
        when(persistenceService.save(any())).thenReturn(1);
        ChatMessageReceivedEvent event = event();

        listener.handleChatMessageReceived(event);

        assertTrue(event.isPersistenceCompleted());
        assertTrue(event.isNewlyPersisted());
        assertFalse(event.isEligibleForRealtimeSideEffects());
        verify(persistenceService).save(any());
    }

    private ChatMessageReceivedEvent event() {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(1L);
        message.setPnmId("message-1");
        message.setSId("session@goofish");
        message.setContentType(1);
        message.setMessageTime(System.currentTimeMillis());
        return new ChatMessageReceivedEvent(this, message, ChatMessageEventSource.REALTIME);
    }

    private XianyuChatMessage persistedMessage(String replyOrigin) {
        XianyuChatMessage message = new XianyuChatMessage();
        message.setId(10L);
        message.setMessageSource("PLATFORM");
        message.setReplyOrigin(replyOrigin);
        return message;
    }
}
