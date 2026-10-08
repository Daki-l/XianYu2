package com.xianyu2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebSocketSyncPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsPackageWhenBodyIsJsonText() {
        Map<String, Object> message = Map.of(
                "body", "{\"syncPushPackage\":{\"pts\":123,\"data\":[{\"data\":\"payload\"}]}}"
        );

        Map<String, Object> payload = WebSocketSyncPayload.extractSyncPushPackage(objectMapper, message);

        assertEquals(123, ((Number) payload.get("pts")).intValue());
        assertEquals(List.of(Map.of("data", "payload")), payload.get("data"));
    }

    @Test
    void extractsPackageWhenThePackageItselfIsJsonText() {
        Map<String, Object> message = Map.of(
                "body", Map.of("syncPushPackage", "{\"seq\":7,\"data\":[{\"data\":\"payload\"}]}"));

        Map<String, Object> payload = WebSocketSyncPayload.extractSyncPushPackage(objectMapper, message);

        assertEquals(7, ((Number) payload.get("seq")).intValue());
        assertFalse(payload.isEmpty());
    }
}
