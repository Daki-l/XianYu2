package com.xianyu2.event.chatMessageEvent.lister;

import com.xianyu2.event.chatMessageEvent.ChatMessageData;
import com.xianyu2.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyu2.service.AccountService;
import com.xianyu2.service.AutoReplyDelayService;
import com.xianyu2.service.AutoReplyService;
import com.xianyu2.service.BuyerProfileService;
import com.xianyu2.service.reply.HumanTakeoverManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageEventAutoReplyListenerTest {

    @Mock
    private AutoReplyDelayService autoReplyDelayService;

    @Mock
    private AccountService accountService;

    @Mock
    private AutoReplyService autoReplyService;

    @Mock
    private HumanTakeoverManager takeoverManager;

    @Mock
    private BuyerProfileService buyerProfileService;

    private ChatMessageEventAutoReplyListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChatMessageEventAutoReplyListener();
        ReflectionTestUtils.setField(listener, "autoReplyDelayService", autoReplyDelayService);
        ReflectionTestUtils.setField(listener, "accountService", accountService);
        ReflectionTestUtils.setField(listener, "autoReplyService", autoReplyService);
        ReflectionTestUtils.setField(listener, "takeoverManager", takeoverManager);
        ReflectionTestUtils.setField(listener, "buyerProfileService", buyerProfileService);
        ReflectionTestUtils.setField(listener, "maxMessageAgeSeconds", 300L);

        when(accountService.getXianyuUserId(1L)).thenReturn("seller");
        when(takeoverManager.isTakenOver(1L, "session@goofish")).thenReturn(false);
        when(autoReplyService.isAutoReplyEnabled(1L, "goods-1")).thenReturn(true);
    }

    @Test
    void submitsDelayTaskForRecentBuyerMessage() {
        ChatMessageData message = buyerMessage(System.currentTimeMillis() - 30_000L);

        listener.handleChatMessageReceived(event(message));

        verify(autoReplyDelayService).submitDelayTask(message);
    }

    @Test
    void skipsDelayTaskForExpiredBuyerMessage() {
        ChatMessageData message = buyerMessage(System.currentTimeMillis() - 301_000L);

        listener.handleChatMessageReceived(event(message));

        verify(autoReplyDelayService, never()).submitDelayTask(message);
    }

    @Test
    void submitsDelayTaskForAHistoryMessageDelayedByTwoMinutes() {
        ChatMessageData message = buyerMessage(System.currentTimeMillis() - 127_000L);

        listener.handleChatMessageReceived(event(message));

        verify(autoReplyDelayService).submitDelayTask(message);
    }

    @Test
    void skipsDelayTaskWhenMessageTimeIsMissing() {
        ChatMessageData message = buyerMessage(null);

        listener.handleChatMessageReceived(event(message));

        verify(autoReplyDelayService, never()).submitDelayTask(message);
    }

    private ChatMessageReceivedEvent event(ChatMessageData message) {
        return new ChatMessageReceivedEvent(this, message);
    }

    private ChatMessageData buyerMessage(Long messageTime) {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(1L);
        message.setPnmId("message-1.PNM");
        message.setSId("session@goofish");
        message.setContentType(1);
        message.setMsgContent("你好");
        message.setSenderUserId("buyer");
        message.setXyGoodsId("goods-1");
        message.setMessageTime(messageTime);
        return message;
    }
}
