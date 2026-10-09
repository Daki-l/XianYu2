package com.xianyu2.service.kami;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部卡密接口响应解析器
 */
public class ExternalKamiResponseParser {

    private final ObjectMapper objectMapper;

    public ExternalKamiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> parse(String responseBody, String resultPath, int quantity) {
        return parseResponse(responseBody, resultPath, null, quantity).contents();
    }

    public ParsedResponse parseResponse(String responseBody, String resultPath,
                                        String externalOrderIdPath, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("卡密数量必须大于0");
        }
        try {
            JsonNode response = objectMapper.readTree(responseBody);
            JsonNode node = resolvePath(response, resultPath);
            List<String> contents = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(item -> addContent(contents, item));
            } else {
                addContent(contents, node);
            }
            if (contents.size() != quantity) {
                throw new IllegalArgumentException("外部接口返回的卡密数量与订单数量不一致");
            }
            return new ParsedResponse(contents, optionalText(resolvePath(response, externalOrderIdPath)));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("外部接口响应不是有效 JSON");
        }
    }

    private void addContent(List<String> contents, JsonNode node) {
        String value = node.isValueNode() ? node.asText().trim() : "";
        if (!value.isEmpty()) {
            contents.add(value);
        }
    }

    private JsonNode resolvePath(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        JsonNode node = root;
        for (String segment : path.trim().split("\\.")) {
            if (!segment.isBlank()) {
                node = node.path(segment);
            }
        }
        return node;
    }

    private String optionalText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    public record ParsedResponse(List<String> contents, String externalOrderId) {
    }
}
