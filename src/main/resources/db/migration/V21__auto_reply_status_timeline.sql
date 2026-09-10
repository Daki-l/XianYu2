-- Store the real transition time for virtual AI status items in the backend timeline.
ALTER TABLE xianyu_goods_auto_reply_record
    ADD COLUMN status_time DATETIME(3) NULL AFTER scheduled_time;

-- Existing records cannot reveal a historical processing/end instant. Preserve only known times.
UPDATE xianyu_goods_auto_reply_record
SET status_time = CASE WHEN state = 0 THEN scheduled_time ELSE create_time END
WHERE status_time IS NULL;
