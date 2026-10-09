-- 租户共享卡密仓库：仓库脱离账号，订单归属改为 (账号, 原始订单号)。

ALTER TABLE xianyu_kami_item
    ADD COLUMN order_account_id BIGINT NULL AFTER order_id;

-- 旧的已核销记录优先使用使用记录中的实际账号；预占/待核对等无使用记录数据回退旧仓库账号。
UPDATE xianyu_kami_item item
JOIN xianyu_kami_config config ON config.id = item.kami_config_id
LEFT JOIN xianyu_kami_usage_record usage_record
    ON usage_record.kami_item_id = item.id
    AND usage_record.order_id = item.order_id
    AND usage_record.tenant_id = item.tenant_id
SET item.order_account_id = COALESCE(usage_record.xianyu_account_id, config.xianyu_account_id)
WHERE item.status IN (1, 2, 3);

ALTER TABLE xianyu_kami_item
    DROP INDEX idx_kami_order,
    ADD KEY idx_kami_order_account_status (order_account_id, order_id, status);

ALTER TABLE xianyu_kami_usage_record
    DROP INDEX uk_usage_item_order,
    ADD UNIQUE KEY uk_usage_item_account_order (kami_item_id, xianyu_account_id, order_id);

ALTER TABLE xianyu_kami_external_request
    DROP INDEX uk_external_kami_order,
    ADD COLUMN external_order_id VARCHAR(200) NULL AFTER order_id,
    ADD UNIQUE KEY uk_external_kami_order_account (tenant_id, kami_config_id, xianyu_account_id, order_id),
    ADD KEY idx_external_kami_supplier_order (tenant_id, external_order_id);

ALTER TABLE xianyu_kami_config
    ADD COLUMN external_api_order_id_path VARCHAR(200) NULL AFTER external_api_result_path;

DROP TRIGGER IF EXISTS trg_kami_config_tenant;

ALTER TABLE xianyu_kami_config
    DROP FOREIGN KEY fk_kami_config_account,
    DROP INDEX idx_kami_config_account,
    DROP COLUMN xianyu_account_id;

-- tenant_id 由租户上下文写入；该触发器不再从账号推导归属。
CREATE TRIGGER trg_kami_config_tenant
    BEFORE INSERT ON xianyu_kami_config
    FOR EACH ROW SET NEW.tenant_id = NEW.tenant_id;
