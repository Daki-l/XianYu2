package com.xianyu2.service.reply;

import com.xianyu2.entity.bo.KeywordReplyRuleBO;
import com.xianyu2.event.chatMessageEvent.ChatMessageData;

import java.util.ArrayList;
import java.util.List;

public interface ReplyStrategy {

    ReplyResult execute(List<ChatMessageData> messageList);

    @lombok.Data
    class ReplyResult {
        private boolean success;
        private List<ReplyItem> items = new ArrayList<>();
        private String matchedKeyword;
        private KeywordReplyRuleBO matchedRule;
        private List<KeywordReplyRuleBO> matchedRules = new ArrayList<>();
        private String errorCode;
        private String errorMessage;

        @lombok.Data
        public static class ReplyItem {
            private String textContent;
            private String imageUrl;
            private int replyType;

            public static ReplyItem text(String text, int replyType) {
                ReplyItem item = new ReplyItem();
                item.setTextContent(text);
                item.setReplyType(replyType);
                return item;
            }

            public static ReplyItem image(String imageUrl, int replyType) {
                ReplyItem item = new ReplyItem();
                item.setImageUrl(imageUrl);
                item.setReplyType(replyType);
                return item;
            }

            public static ReplyItem textAndImage(String text, String imageUrl, int replyType) {
                ReplyItem item = new ReplyItem();
                item.setTextContent(text);
                item.setImageUrl(imageUrl);
                item.setReplyType(replyType);
                return item;
            }
        }

        public static ReplyResult of(List<ReplyItem> items) {
            ReplyResult r = new ReplyResult();
            r.setSuccess(true);
            r.setItems(items);
            return r;
        }

        public static ReplyResult fail() {
            ReplyResult r = new ReplyResult();
            r.setSuccess(false);
            return r;
        }

        public static ReplyResult fail(String errorCode, String errorMessage) {
            ReplyResult r = fail();
            r.setErrorCode(errorCode);
            r.setErrorMessage(errorMessage);
            return r;
        }
    }
}
