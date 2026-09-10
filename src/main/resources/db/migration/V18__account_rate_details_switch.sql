ALTER TABLE xianyu_account
    ADD COLUMN merchant_rate_details_enabled TINYINT NOT NULL DEFAULT 1 AFTER status;
