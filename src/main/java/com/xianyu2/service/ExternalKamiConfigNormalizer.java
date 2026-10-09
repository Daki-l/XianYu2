package com.xianyu2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.controller.dto.KamiConfigReqDTO;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Normalizes legacy clients that serialize external API settings one escaping layer too far.
 */
public final class ExternalKamiConfigNormalizer {

    private static final int MAX_QUOTE_ESCAPE_LAYERS = 2;
    private static final Pattern BARE_TEMPLATE_PLACEHOLDER = Pattern.compile(
            "([:\\[,])\\s*\\{(orderId|quantity|requestToken|accountId|clientOrderNo)}(?=\\s*[,}\\]])");

    private ExternalKamiConfigNormalizer() {
    }

    public static void normalize(KamiConfigReqDTO request, ObjectMapper objectMapper) {
        request.setExternalApiUrl(normalizeUrl(request.getExternalApiUrl()));
        request.setExternalApiHeaders(normalizeJsonObject(request.getExternalApiHeaders(), objectMapper, false));
        request.setExternalApiBody(normalizeJsonObject(request.getExternalApiBody(), objectMapper, true));
    }

    public static JsonNode readBodyTemplate(String value, ObjectMapper objectMapper) throws IOException {
        try {
            return objectMapper.readTree(value);
        } catch (IOException e) {
            return objectMapper.readTree(quoteBareTemplatePlaceholders(value));
        }
    }

    private static String normalizeUrl(String value) {
        if (value == null) {
            return null;
        }
        return value.trim()
                .replace("\\:", ":")
                .replace("\\.", ".")
                .replace("\\/", "/");
    }

    private static String normalizeJsonObject(String value, ObjectMapper objectMapper,
                                              boolean allowBareTemplatePlaceholders) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String candidate = value.trim();
        for (int attempt = 0; attempt <= MAX_QUOTE_ESCAPE_LAYERS; attempt++) {
            JsonNode node = tryReadTree(candidate, objectMapper);
            if (node != null) {
                if (node.isObject()) {
                    try {
                        return objectMapper.writeValueAsString(node);
                    } catch (IOException e) {
                        return value;
                    }
                }
                if (node.isTextual()) {
                    candidate = node.textValue();
                    continue;
                }
                return value;
            }

            if (allowBareTemplatePlaceholders) {
                JsonNode template = tryReadTree(quoteBareTemplatePlaceholders(candidate), objectMapper);
                if (template != null && template.isObject()) {
                    return candidate;
                }
            }

            String unescaped = candidate.replace("\\\"", "\"");
            if (unescaped.equals(candidate)) {
                break;
            }
            candidate = unescaped;
        }
        return value;
    }

    private static String quoteBareTemplatePlaceholders(String value) {
        return BARE_TEMPLATE_PLACEHOLDER.matcher(value).replaceAll("$1\"{$2}\"");
    }

    private static JsonNode tryReadTree(String value, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException e) {
            return null;
        }
    }
}
