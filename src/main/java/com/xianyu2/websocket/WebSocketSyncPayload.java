package com.xianyu2.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.utils.MessageDecryptUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts a sync package from the wire formats currently returned by the IM gateway.
 *
 * <p>The gateway may return the body or {@code syncPushPackage} as an object, JSON text,
 * or MessagePack text. Keeping this normalization at the WebSocket boundary ensures that
 * message persistence and cursor advancement consume the same package.</p>
 */
public final class WebSocketSyncPayload {

    private WebSocketSyncPayload() {
    }

    public static Map<String, Object> extractSyncPushPackage(
            ObjectMapper objectMapper, Map<String, Object> messageData) {
        if (messageData == null) {
            return Map.of();
        }
        Map<String, Object> body = toMap(objectMapper, messageData.get("body"));
        if (body.isEmpty()) {
            body = toMap(objectMapper, messageData.get("decryptedBody"));
        }
        if (body.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> syncPackage = toMap(objectMapper, body.get("syncPushPackage"));
        if (hasDataList(syncPackage)) {
            return syncPackage;
        }
        return hasDataList(body) ? body : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(ObjectMapper objectMapper, Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, child) -> result.put(String.valueOf(key), child));
            return result;
        }
        if (value instanceof List<?> values) {
            for (Object item : values) {
                Map<String, Object> map = toMap(objectMapper, item);
                if (!map.isEmpty()) {
                    return map;
                }
            }
            return Map.of();
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return Map.of();
        }

        Map<String, Object> jsonMap = readJsonMap(objectMapper, text);
        if (!jsonMap.isEmpty()) {
            return jsonMap;
        }
        String decoded = MessageDecryptUtils.tryDecrypt(text);
        if (decoded.equals(text)) {
            return Map.of();
        }
        return readJsonMap(objectMapper, decoded);
    }

    private static Map<String, Object> readJsonMap(ObjectMapper objectMapper, String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static boolean hasDataList(Map<String, Object> candidate) {
        return candidate.get("data") instanceof List<?>;
    }
}
