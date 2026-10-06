INSERT INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('platform_risk_cooldown_minutes', '30', '平台风控固定冷却时长（分钟，1至1440）')
ON DUPLICATE KEY UPDATE setting_desc = VALUES(setting_desc);

ALTER TABLE xianyu_buyer_profile
    ADD COLUMN buyer_avatar_url VARCHAR(1000) NULL AFTER buyer_user_name,
    ADD COLUMN platform_profile_json TEXT NULL AFTER buyer_avatar_url,
    ADD COLUMN profile_fetched_at DATETIME(3) NULL AFTER last_interaction_time,
    ADD KEY idx_buyer_profile_fetched (xianyu_account_id, profile_fetched_at);
