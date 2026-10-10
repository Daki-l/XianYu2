package com.xianyu2.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.config.WebSocketConfig;
import com.xianyu2.event.chatMessageEvent.ChatMessageEventSource;
import com.xianyu2.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyu2.service.WebSocketHealthTracker;
import com.xianyu2.utils.AccountDisplayNameUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SyncMessageHandlerTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AccountDisplayNameUtils displayNameUtils;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SyncMessageHandler handler;
    private WebSocketHealthTracker healthTracker;

    @BeforeEach
    void setUp() {
        handler = new SyncMessageHandler();
        healthTracker = new WebSocketHealthTracker();
        healthTracker.recordConnectionEstablished(2L);
        ReflectionTestUtils.setField(handler, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(handler, "webSocketConfig", new WebSocketConfig());
        ReflectionTestUtils.setField(handler, "healthTracker", healthTracker);
        ReflectionTestUtils.setField(handler, "displayNameUtils", displayNameUtils);
    }

    @Test
    void publishesBuyerMessageWhenSyncBodyIsJsonText() throws Exception {
        String encryptedMessage = Base64.getEncoder().encodeToString(packBuyerMessage());
        String body = objectMapper.writeValueAsString(Map.of(
                "syncPushPackage", Map.of("data", List.of(Map.of("data", encryptedMessage)))));

        handler.handle("2", Map.of("lwp", "/s/para", "body", body));

        ArgumentCaptor<ChatMessageReceivedEvent> event = ArgumentCaptor.forClass(ChatMessageReceivedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals("message-1", event.getValue().getMessageData().getPnmId());
        assertEquals("session@goofish", event.getValue().getMessageData().getSId());
        assertEquals("goods-1", event.getValue().getMessageData().getXyGoodsId());
        assertEquals(1, event.getValue().getMessageData().getContentType());
        assertEquals(ChatMessageEventSource.REALTIME, event.getValue().getReceiptSource());
    }

    @Test
    void classifiesOldSellerMessageAsWebSocketCatchup() throws Exception {
        long oldMessageTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(3);
        String encryptedMessage = Base64.getEncoder().encodeToString(
                packMessage("seller-message-1", "seller-id", oldMessageTime));
        String body = objectMapper.writeValueAsString(Map.of(
                "syncPushPackage", Map.of("data", List.of(Map.of("data", encryptedMessage)))));

        handler.handle("2", Map.of("lwp", "/s/para", "body", body));

        ArgumentCaptor<ChatMessageReceivedEvent> event = ArgumentCaptor.forClass(ChatMessageReceivedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(ChatMessageEventSource.WEBSOCKET_CATCHUP, event.getValue().getReceiptSource());
    }

    private byte[] packBuyerMessage() throws Exception {
        return packMessage("message-1", "buyer-id", System.currentTimeMillis());
    }

    private byte[] packMessage(String pnmId, String senderUserId, long messageTime) throws Exception {
        String content = "{\"contentType\":1}";
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packMapHeader(1);
            packer.packString("1");
            packer.packMapHeader(5);
            packer.packString("3");
            packer.packString(pnmId);
            packer.packString("2");
            packer.packString("session@goofish");
            packer.packString("5");
            packer.packLong(messageTime);
            packer.packString("6");
            packer.packMapHeader(1);
            packer.packString("3");
            packer.packMapHeader(1);
            packer.packString("5");
            packer.packString(content);
            packer.packString("10");
            packer.packMapHeader(4);
            packer.packString("reminderContent");
            packer.packString("buyer: hello");
            packer.packString("reminderTitle");
            packer.packString("buyer");
            packer.packString("senderUserId");
            packer.packString(senderUserId);
            packer.packString("reminderUrl");
            packer.packString("https://www.goofish.com/item?itemId=goods-1");
            packer.close();
            return packer.toByteArray();
        }
    }
}
