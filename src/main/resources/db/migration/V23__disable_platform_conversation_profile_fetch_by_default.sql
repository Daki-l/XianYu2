INSERT INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('platform_conversation_profile_fetch_enabled', 'false',
        '是否允许向闲鱼查询会话买家资料（true启用，false关闭）')
ON DUPLICATE KEY UPDATE setting_desc = VALUES(setting_desc);
