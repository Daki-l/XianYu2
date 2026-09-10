package com.xianyu2.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 跨平台消息与本地发送记录的去重规则。
 */
public final class ChatMessageDeduplication {

    public static final int PLATFORM_CONTENT_TYPE = 1;
    public static final int LOCAL_AI_CONTENT_TYPE = 888;
    public static final int LOCAL_MANUAL_REPLY_CONTENT_TYPE = 999;
    public static final String PLATFORM_SOURCE = "PLATFORM";
    public static final String LOCAL_SOURCE = "LOCAL";
    public static final String LOCAL_AI_SOURCE = "LOCAL_AI";

    private ChatMessageDeduplication() {
    }

    public static String normalizeContent(String content) {
        return content == null ? "" : content.trim().replaceAll("\\s+", " ");
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
        return contentType != null && contentType == PLATFORM_CONTENT_TYPE
                && PLATFORM_SOURCE.equals(source);
    }

    public static boolean isLocalAiCandidate(Integer contentType, String source) {
        return contentType != null && contentType == LOCAL_AI_CONTENT_TYPE
                && LOCAL_AI_SOURCE.equals(source);
    }

    public static boolean isLocalManualReplyCandidate(Integer contentType, String source) {
        return contentType != null && contentType == LOCAL_MANUAL_REPLY_CONTENT_TYPE
                && LOCAL_SOURCE.equals(source);
    }

    public static String sourceForContentType(Integer contentType) {
        if (contentType != null && contentType == LOCAL_AI_CONTENT_TYPE) {
            return LOCAL_AI_SOURCE;
        }
        if (contentType != null && (contentType == 887 || contentType == 997
                || contentType == LOCAL_MANUAL_REPLY_CONTENT_TYPE)) {
            return LOCAL_SOURCE;
        }
        return PLATFORM_SOURCE;
    }
}
