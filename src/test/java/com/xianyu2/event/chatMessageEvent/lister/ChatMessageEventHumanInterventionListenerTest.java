package com.xianyu2.event.chatMessageEvent.lister;

import com.xianyu2.entity.XianyuChatMessage;
import com.xianyu2.event.chatMessageEvent.ChatMessageData;
import com.xianyu2.event.chatMessageEvent.ChatMessageEventSource;
import com.xianyu2.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyu2.service.AccountService;
import com.xianyu2.service.AutoReplyDelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageEventHumanInterventionListenerTest {

    @Mock
    private AutoReplyDelayService autoReplyDelayService;

    @Mock
    private AccountService accountService;

    private ChatMessageEventHumanInterventionListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChatMessageEventHumanInterventionListener();
        ReflectionTestUtils.setField(listener, "autoReplyDelayService", autoReplyDelayService);
        ReflectionTestUtils.setField(listener, "accountService", accountService);
        ReflectionTestUtils.setField(listener, "maxMessageAgeSeconds", 300L);
    }

    @Test
    void createsTakeoverForANewRealtimeSellerMessage() {
        when(accountService.getXianyuUserId(1L)).thenReturn("seller-id");
        ChatMessageReceivedEvent event = persistedEvent(
                ChatMessageEventSource.REALTIME, System.currentTimeMillis(), true, null);

        listener.handleChatMessageReceived(event);

        verify(autoReplyDelayService).recordSellerManualReply(1L, "goods-1", "session@goofish");
    }

    @Test
    void ignoresOldSellerMessageReplayedAfterReconnect() {
        ChatMessageReceivedEvent event = persistedEvent(
                ChatMessageEventSource.WEBSOCKET_CATCHUP,
                System.currentTimeMillis() - TimeUnit.HOURS.toMillis(3), true, null);

        listener.handleChatMessageReceived(event);

        verify(autoReplyDelayService, never()).recordSellerManualReply(1L, "goods-1", "session@goofish");
    }

    @Test
    void ignoresSellerMessageInsertedByExplicitHistorySync() {
        ChatMessageReceivedEvent event = persistedEvent(
                ChatMessageEventSource.PLATFORM_HISTORY, System.currentTimeMillis(), true, null);

        listener.handleChatMessageReceived(event);

        verify(autoReplyDelayService, never()).recordSellerManualReply(1L, "goods-1", "session@goofish");
    }

    @Test
    void ignoresDuplicatePlatformMessage() {
        ChatMessageReceivedEvent event = persistedEvent(
                ChatMessageEventSource.REALTIME, System.currentTimeMillis(), false, null);

        listener.handleChatMessageReceived(event);

        verify(autoReplyDelayService, never()).recordSellerManualReply(1L, "goods-1", "session@goofish");
    }

    @Test
    void ignoresLocalAiEcho() {
        XianyuChatMessage persisted = new XianyuChatMessage();
        persisted.setReplyOrigin("AI");
        ChatMessageReceivedEvent event = persistedEvent(
                ChatMessageEventSource.REALTIME, System.currentTimeMillis(), true, persisted);

        listener.handleChatMessageReceived(event);

        verify(autoReplyDelayService, never()).recordSellerManualReply(1L, "goods-1", "session@goofish");
    }

    @Test
    void ignoresLocalBackendEcho() {
        XianyuChatMessage persisted = new XianyuChatMessage();
        persisted.setReplyOrigin("BACKEND");
        ChatMessageReceivedEvent event = persistedEvent(
                ChatMessageEventSource.REALTIME, System.currentTimeMillis(), true, persisted);

        listener.handleChatMessageReceived(event);

        verify(autoReplyDelayService, never()).recordSellerManualReply(1L, "goods-1", "session@goofish");
    }

    @Test
    void ignoresExpiredRealtimeSellerMessageAsSecondLineOfDefense() {
        when(accountService.getXianyuUserId(1L)).thenReturn("seller-id");
        ChatMessageReceivedEvent event = persistedEvent(
                ChatMessageEventSource.REALTIME,
                System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(301), true, null);

        listener.handleChatMessageReceived(event);

        verify(autoReplyDelayService, never()).recordSellerManualReply(1L, "goods-1", "session@goofish");
    }

    private ChatMessageReceivedEvent persistedEvent(ChatMessageEventSource source, long messageTime,
                                                     boolean newlyPersisted,
                                                     XianyuChatMessage persistedMessage) {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(1L);
        message.setPnmId("message-1");
        message.setSId("session@goofish");
        message.setXyGoodsId("goods-1");
        message.setContentType(1);
        message.setSenderUserId("seller-id");
        message.setMessageTime(messageTime);
        ChatMessageReceivedEvent event = new ChatMessageReceivedEvent(this, message, source);
        event.markPersistenceResult(newlyPersisted, persistedMessage);
        return event;
    }
}
