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

    private static final int MAX_NESTING_DEPTH = 8;

    private WebSocketSyncPayload() {
    }

    public static Map<String, Object> extractSyncPushPackage(
            ObjectMapper objectMapper, Map<String, Object> messageData) {
        if (messageData == null) {
            return Map.of();
        }
        Map<String, Object> syncPackage = findSyncPushPackage(
                objectMapper, messageData.get("body"), 0);
        return syncPackage.isEmpty()
                ? findSyncPushPackage(objectMapper, messageData.get("decryptedBody"), 0)
                : syncPackage;
    }

    private static Map<String, Object> findSyncPushPackage(
            ObjectMapper objectMapper, Object value, int depth) {
        if (value == null || depth > MAX_NESTING_DEPTH) {
            return Map.of();
        }
        Object normalized = normalize(objectMapper, value);
        if (normalized instanceof Map<?, ?> source) {
            Map<String, Object> map = new LinkedHashMap<>();
            source.forEach((key, child) -> map.put(String.valueOf(key), child));

            Map<String, Object> namedPackage = findSyncPushPackage(
                    objectMapper, map.get("syncPushPackage"), depth + 1);
            if (!namedPackage.isEmpty()) {
                return namedPackage;
            }
            if (hasDataList(map)) {
                return map;
            }
            for (Object child : map.values()) {
                Map<String, Object> nested = findSyncPushPackage(objectMapper, child, depth + 1);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
            return Map.of();
        }
        if (normalized instanceof List<?> values) {
            for (Object item : values) {
                Map<String, Object> nested = findSyncPushPackage(objectMapper, item, depth + 1);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
        }
        return Map.of();
    }

    private static Object normalize(ObjectMapper objectMapper, Object value) {
        if (value instanceof Map<?, ?> source) {
            return source;
        }
        if (value instanceof List<?>) {
            return value;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }

        Object jsonValue = readJsonValue(objectMapper, text);
        if (jsonValue != null) {
            return jsonValue;
        }
        if (!looksLikeBase64(text)) {
            return null;
        }
        String decoded = MessageDecryptUtils.tryDecrypt(text);
        return decoded.equals(text) ? null : readJsonValue(objectMapper, decoded);
    }

    private static Object readJsonValue(ObjectMapper objectMapper, String value) {
        try {
            Object parsed = objectMapper.readValue(value, new TypeReference<Object>() { });
            return parsed instanceof Map<?, ?> || parsed instanceof List<?> ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean looksLikeBase64(String value) {
        return value.length() >= 8 && value.matches("[A-Za-z0-9+/]+={0,2}");
    }

    private static boolean hasDataList(Map<String, Object> candidate) {
        if (!(candidate.get("data") instanceof List<?> dataItems)) {
            return false;
        }
        return dataItems.stream().anyMatch(item -> item instanceof Map<?, ?> map
                && map.containsKey("data"));
    }
}
