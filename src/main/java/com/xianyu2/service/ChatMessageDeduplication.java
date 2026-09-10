package com.xianyu2.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 跨平台消息与本地发送记录的去重规则。
 */
public final class ChatMessageDeduplication {

    public static final int PLATFORM_TEXT_CONTENT_TYPE = 1;
    public static final int PLATFORM_IMAGE_CONTENT_TYPE = 2;
    public static final int LOCAL_AI_TEXT_CONTENT_TYPE = 888;
    public static final int LOCAL_AI_IMAGE_CONTENT_TYPE = 887;
    public static final int LOCAL_MANUAL_TEXT_CONTENT_TYPE = 999;
    public static final int LOCAL_MANUAL_IMAGE_CONTENT_TYPE = 997;
    public static final String PLATFORM_SOURCE = "PLATFORM";
    public static final String LOCAL_SOURCE = "LOCAL";
    public static final String LOCAL_AI_SOURCE = "LOCAL_AI";

    private ChatMessageDeduplication() {
    }

    public static String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.trim().replaceAll("\\s+", " ");
        if (normalized.startsWith("[图片]")) {
            normalized = normalized.substring("[图片]".length());
        }
        return normalized.startsWith("http://") ? "https://" + normalized.substring(7) : normalized;
    }

    public static String fingerprint(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizeContent(content).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }

    public static boolean isPlatformCandidate(Integer contentType, String source) {
        return contentType != null && (contentType == PLATFORM_TEXT_CONTENT_TYPE
                || contentType == PLATFORM_IMAGE_CONTENT_TYPE)
                && PLATFORM_SOURCE.equals(source);
    }

    public static boolean isLocalAiCandidate(Integer contentType, String source) {
        return contentType != null && (contentType == LOCAL_AI_TEXT_CONTENT_TYPE
                || contentType == LOCAL_AI_IMAGE_CONTENT_TYPE)
                && LOCAL_AI_SOURCE.equals(source);
    }

    public static boolean isLocalManualReplyCandidate(Integer contentType, String source) {
        return contentType != null && (contentType == LOCAL_MANUAL_TEXT_CONTENT_TYPE
                || contentType == LOCAL_MANUAL_IMAGE_CONTENT_TYPE)
                && LOCAL_SOURCE.equals(source);
    }

    public static boolean supportsCrossSourceReconciliation(Integer contentType) {
        return contentType != null && (contentType == PLATFORM_TEXT_CONTENT_TYPE
                || contentType == PLATFORM_IMAGE_CONTENT_TYPE
                || contentType == LOCAL_AI_TEXT_CONTENT_TYPE
                || contentType == LOCAL_AI_IMAGE_CONTENT_TYPE
                || contentType == LOCAL_MANUAL_TEXT_CONTENT_TYPE
                || contentType == LOCAL_MANUAL_IMAGE_CONTENT_TYPE);
    }

    public static Integer localAiContentTypeForPlatform(Integer platformContentType) {
        if (platformContentType == null) {
            return null;
        }
        return switch (platformContentType) {
            case PLATFORM_TEXT_CONTENT_TYPE -> LOCAL_AI_TEXT_CONTENT_TYPE;
            case PLATFORM_IMAGE_CONTENT_TYPE -> LOCAL_AI_IMAGE_CONTENT_TYPE;
            default -> null;
        };
    }

    public static Integer localManualContentTypeForPlatform(Integer platformContentType) {
        if (platformContentType == null) {
            return null;
        }
        return switch (platformContentType) {
            case PLATFORM_TEXT_CONTENT_TYPE -> LOCAL_MANUAL_TEXT_CONTENT_TYPE;
            case PLATFORM_IMAGE_CONTENT_TYPE -> LOCAL_MANUAL_IMAGE_CONTENT_TYPE;
            default -> null;
        };
    }

    public static Integer platformContentTypeForLocal(Integer localContentType) {
        if (localContentType == null) {
            return null;
        }
        return switch (localContentType) {
            case LOCAL_AI_TEXT_CONTENT_TYPE, LOCAL_MANUAL_TEXT_CONTENT_TYPE -> PLATFORM_TEXT_CONTENT_TYPE;
            case LOCAL_AI_IMAGE_CONTENT_TYPE, LOCAL_MANUAL_IMAGE_CONTENT_TYPE -> PLATFORM_IMAGE_CONTENT_TYPE;
            default -> null;
        };
    }

    public static String sourceForContentType(Integer contentType) {
        if (contentType != null && (contentType == LOCAL_AI_TEXT_CONTENT_TYPE
                || contentType == LOCAL_AI_IMAGE_CONTENT_TYPE)) {
            return LOCAL_AI_SOURCE;
        }
        if (contentType != null && (contentType == LOCAL_MANUAL_IMAGE_CONTENT_TYPE
                || contentType == LOCAL_MANUAL_TEXT_CONTENT_TYPE)) {
            return LOCAL_SOURCE;
        }
        return PLATFORM_SOURCE;
    }
}
