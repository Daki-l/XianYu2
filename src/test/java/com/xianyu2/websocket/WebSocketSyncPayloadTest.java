package com.xianyu2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.junit.jupiter.api.Test;

import java.util.Base64;
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

    @Test
    void extractsPackageFromJsonArrayWrapper() {
        Map<String, Object> message = Map.of(
                "body", "[{\"syncPushPackage\":{\"seq\":8,\"data\":[{\"data\":\"payload\"}]}}]");

        Map<String, Object> payload = WebSocketSyncPayload.extractSyncPushPackage(objectMapper, message);

        assertEquals(8, ((Number) payload.get("seq")).intValue());
    }

    @Test
    void extractsPackageFromMessagePackBody() throws Exception {
        Map<String, Object> message = Map.of("body", Base64.getEncoder().encodeToString(packSyncPackage()));

        Map<String, Object> payload = WebSocketSyncPayload.extractSyncPushPackage(objectMapper, message);

        assertEquals(9, ((Number) payload.get("seq")).intValue());
        assertEquals(List.of(Map.of("data", "payload")), payload.get("data"));
    }

    private byte[] packSyncPackage() throws Exception {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packMapHeader(1);
            packer.packString("syncPushPackage");
            packer.packMapHeader(2);
            packer.packString("seq");
            packer.packLong(9);
            packer.packString("data");
            packer.packArrayHeader(1);
            packer.packMapHeader(1);
            packer.packString("data");
            packer.packString("payload");
            packer.close();
            return packer.toByteArray();
        }
    }
}
