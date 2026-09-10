package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformHistoryMessageParserTest {

    private final PlatformHistoryMessageParser parser = new PlatformHistoryMessageParser(new ObjectMapper());

    @Test
    void parsesNestedCreatedAtAsTheMessageTimestamp() {
        long createdAt = 1_788_953_954_750L;

        List<XianyuChatMessage> messages = parser.parse(1L, "sid@goofish",
                List.of(historyModel(createdAt)));

        assertEquals(1, messages.size());
        assertEquals(createdAt, messages.getFirst().getMessageTime());
    }

    @Test
    void skipsHistoryMessagesWithoutATrustedTimestamp() {
        List<XianyuChatMessage> messages = parser.parse(1L, "sid@goofish",
                List.of(historyModel(null)));

        assertTrue(messages.isEmpty());
    }

    private Map<String, Object> historyModel(Long createdAt) {
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("messageId", "message-id");
        message.put("cid", "sid@goofish");
        message.put("createdAt", createdAt);
        message.put("extension", Map.of("senderUserId", "seller-user"));
        message.put("content", Map.of("custom", Map.of("data", encodedTextContent("reply content"))));
        return Map.of("message", message);
    }

    private String encodedTextContent(String text) {
        String content = "{\"contentType\":1,\"text\":{\"text\":\"" + text + "\"}}";
        return Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }
}
