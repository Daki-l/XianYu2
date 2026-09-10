package com.xianyu2.service.bo;

import lombok.Data;

import java.util.List;

/**
 * RAG回复结果
 * 包含AI回复内容和RAG命中的资料详情
 */
@Data
public class RAGReplyResult {

    /** Whether the model produced a reply that is safe to send to a buyer. */
    private boolean success;

    /** AI回复内容 */
    private String replyContent;

    /** A stable, safe-to-display failure code for callers that need to persist it. */
    private String errorCode;

    /** A concise, sanitized failure reason. It must never contain provider payloads. */
    private String errorMessage;
    
    /** RAG命中的资料详情列表 */
    private List<RAGHitDetail> hitDetails;
    
    /**
     * RAG命中资料详情
     */
    @Data
    public static class RAGHitDetail {
        /** 文档ID */
        private String documentId;
        /** 命中的文本内容 */
        private String content;
        /** 相似度得分 */
        private Double score;
    }
}
