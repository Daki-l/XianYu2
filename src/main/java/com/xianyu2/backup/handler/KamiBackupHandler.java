package com.xianyu2.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyu2.backup.DataBackupHandler;
import com.xianyu2.common.ResultObject;
import com.xianyu2.controller.dto.KamiConfigReqDTO;
import com.xianyu2.controller.dto.KamiConfigRespDTO;
import com.xianyu2.entity.XianyuKamiConfig;
import com.xianyu2.entity.XianyuKamiItem;
import com.xianyu2.mapper.XianyuKamiConfigMapper;
import com.xianyu2.mapper.XianyuKamiItemMapper;
import com.xianyu2.service.KamiConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class KamiBackupHandler implements DataBackupHandler {

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Autowired
    private XianyuKamiItemMapper kamiItemMapper;

    @Autowired
    private KamiConfigService kamiConfigService;

    @Override
    public String getModuleKey() {
        return "kami";
    }

    @Override
    public String getModuleName() {
        return "卡密仓库";
    }

    @Override
    public Map<String, Object> exportData() {
        List<XianyuKamiConfig> kamiConfigs = kamiConfigMapper.selectList(null);
        List<Map<String, Object>> configList = new ArrayList<>();
        List<Map<String, Object>> itemList = new ArrayList<>();

        for (XianyuKamiConfig config : kamiConfigs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sourceId", config.getId());
            map.put("aliasName", config.getAliasName());
            map.put("sourceType", config.getSourceType());
            map.put("externalApiUrl", config.getExternalApiUrl());
            map.put("externalApiBody", config.getExternalApiBody());
            map.put("externalApiResultPath", config.getExternalApiResultPath());
            map.put("externalApiOrderIdPath", config.getExternalApiOrderIdPath());
            map.put("externalApiTimeoutSeconds", config.getExternalApiTimeoutSeconds());
            map.put("alertEnabled", config.getAlertEnabled());
            map.put("alertThresholdType", config.getAlertThresholdType());
            map.put("alertThresholdValue", config.getAlertThresholdValue());
            map.put("alertEmail", config.getAlertEmail());
            configList.add(map);

            for (XianyuKamiItem item : kamiItemMapper.findByConfigIdAndStatus(config.getId(), 0)) {
                Map<String, Object> itemMap = new LinkedHashMap<>();
                itemMap.put("sourceId", config.getId());
                itemMap.put("kamiContent", item.getKamiContent());
                itemList.add(itemMap);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kamiConfigs", configList);
        data.put("kamiItems", itemList);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) {
            return;
        }

        Map<String, Long> sourceIdToId = new HashMap<>();
        Map<String, Long> legacyConfigKeyToId = new HashMap<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configMaps = (List<Map<String, Object>>) data.get("kamiConfigs");
        if (configMaps != null) {
            for (Map<String, Object> map : configMaps) {
                try {
                    String aliasName = text(map.get("aliasName"));
                    if (aliasName == null) {
                        log.warn("[KamiBackup] 跳过无仓库名称的配置");
                        continue;
                    }

                    XianyuKamiConfig existing = findExistingConfig(aliasName);
                    KamiConfigReqDTO request = new KamiConfigReqDTO();
                    request.setId(existing == null ? null : existing.getId());
                    request.setAliasName(aliasName);
                    request.setSourceType(map.get("sourceType") == null ? "LOCAL" : text(map.get("sourceType")));
                    request.setExternalApiUrl(text(map.get("externalApiUrl")));
                    request.setExternalApiBody(text(map.get("externalApiBody")));
                    request.setExternalApiResultPath(text(map.get("externalApiResultPath")));
                    request.setExternalApiOrderIdPath(text(map.get("externalApiOrderIdPath")));
                    request.setExternalApiTimeoutSeconds(integer(map.get("externalApiTimeoutSeconds"), 10));
                    request.setAlertEnabled(integer(map.get("alertEnabled"), null));
                    request.setAlertThresholdType(integer(map.get("alertThresholdType"), null));
                    request.setAlertThresholdValue(integer(map.get("alertThresholdValue"), null));
                    request.setAlertEmail(text(map.get("alertEmail")));

                    ResultObject<KamiConfigRespDTO> saveResult = kamiConfigService.createOrUpdateConfig(request);
                    if (!Integer.valueOf(200).equals(saveResult.getCode()) || saveResult.getData() == null) {
                        throw new IllegalArgumentException(saveResult.getMsg());
                    }
                    Long targetId = saveResult.getData().getId();
                    String sourceId = text(map.get("sourceId"));
                    if (sourceId != null) {
                        sourceIdToId.put(sourceId, targetId);
                    }
                    String legacyKey = legacyConfigKey(map, aliasName);
                    if (legacyKey != null) {
                        legacyConfigKeyToId.put(legacyKey, targetId);
                    }
                } catch (Exception e) {
                    log.warn("[KamiBackup] 导入单条卡密配置失败: {}", e.getMessage());
                }
            }
        }
        context.put("kamiConfigIdMap", sourceIdToId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemMaps = (List<Map<String, Object>>) data.get("kamiItems");
        if (itemMaps == null) {
            return;
        }
        int skippedCount = 0;
        for (Map<String, Object> map : itemMaps) {
            try {
                String kamiContent = text(map.get("kamiContent"));
                if (kamiContent == null) {
                    continue;
                }
                Long configId = sourceIdToId.get(text(map.get("sourceId")));
                if (configId == null) {
                    String legacyKey = legacyConfigKey(map, text(map.get("aliasName")));
                    configId = legacyKey == null ? null : legacyConfigKeyToId.get(legacyKey);
                }
                if (configId == null) {
                    skippedCount++;
                    continue;
                }

                LambdaQueryWrapper<XianyuKamiItem> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(XianyuKamiItem::getKamiConfigId, configId)
                        .eq(XianyuKamiItem::getKamiContent, kamiContent);
                if (kamiItemMapper.selectOne(wrapper) != null) {
                    continue;
                }
                XianyuKamiItem item = new XianyuKamiItem();
                item.setKamiConfigId(configId);
                item.setKamiContent(kamiContent);
                item.setStatus(0);
                item.setSortOrder(kamiItemMapper.countByConfigId(configId));
                kamiItemMapper.insert(item);
            } catch (Exception e) {
                log.warn("[KamiBackup] 导入单条卡密项失败: {}", e.getMessage());
            }
        }
        if (skippedCount > 0) {
            log.warn("[KamiBackup] 共跳过 {} 条卡密项数据（找不到 sourceId 映射）", skippedCount);
        }
    }

    private XianyuKamiConfig findExistingConfig(String aliasName) {
        List<XianyuKamiConfig> byAlias = kamiConfigMapper.selectList(
                new LambdaQueryWrapper<XianyuKamiConfig>()
                        .eq(XianyuKamiConfig::getAliasName, aliasName));
        return byAlias.size() == 1 ? byAlias.getFirst() : null;
    }

    private String legacyConfigKey(Map<String, Object> map, String aliasName) {
        String unb = text(map.get("unb"));
        return unb == null || aliasName == null ? null : unb + ":" + aliasName;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private Integer integer(Object value, Integer defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String text = text(value);
            return text == null ? defaultValue : Integer.valueOf(text);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
