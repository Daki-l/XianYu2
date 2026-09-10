-- 区分平台消息与本地 AI 回复，并保留跨来源重复记录的审计关系。
ALTER TABLE xianyu_chat_message
    ADD COLUMN message_source VARCHAR(16) NOT NULL DEFAULT 'PLATFORM' AFTER complete_msg,
    ADD COLUMN dedupe_fingerprint CHAR(64) NULL AFTER message_source,
    ADD COLUMN duplicate_of_id BIGINT NULL AFTER dedupe_fingerprint,
    ADD COLUMN duplicate_status TINYINT NOT NULL DEFAULT 0 AFTER duplicate_of_id,
    ADD COLUMN reply_origin VARCHAR(16) NULL AFTER duplicate_status,
    ADD KEY idx_chat_dedupe_candidate (
        xianyu_account_id, s_id, message_source, content_type,
        sender_user_id, message_time, duplicate_status
    ),
    ADD KEY idx_chat_duplicate_status (xianyu_account_id, s_id, duplicate_status, message_time);

-- 项目主动保存的回复标记为本地来源，888 额外保留 AI 来源。
UPDATE xianyu_chat_message
SET message_source = 'LOCAL_AI', reply_origin = 'AI'
WHERE content_type = 888;

UPDATE xianyu_chat_message
SET message_source = 'LOCAL'
WHERE content_type IN (887, 997, 999);
