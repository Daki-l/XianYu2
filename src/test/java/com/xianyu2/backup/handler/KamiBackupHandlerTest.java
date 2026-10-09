package com.xianyu2.backup.handler;

import com.xianyu2.common.ResultObject;
import com.xianyu2.controller.dto.KamiConfigReqDTO;
import com.xianyu2.controller.dto.KamiConfigRespDTO;
import com.xianyu2.mapper.XianyuKamiConfigMapper;
import com.xianyu2.mapper.XianyuKamiItemMapper;
import com.xianyu2.service.KamiConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KamiBackupHandlerTest {

    @Mock
    private XianyuKamiConfigMapper configMapper;
    @Mock
    private XianyuKamiItemMapper itemMapper;
    @Mock
    private KamiConfigService kamiConfigService;

    @Test
    void mapsSourceIdToTheCreatedWarehouseInsteadOfMatchingTheTargetNumericId() {
        KamiBackupHandler handler = new KamiBackupHandler();
        ReflectionTestUtils.setField(handler, "kamiConfigMapper", configMapper);
        ReflectionTestUtils.setField(handler, "kamiItemMapper", itemMapper);
        ReflectionTestUtils.setField(handler, "kamiConfigService", kamiConfigService);

        KamiConfigRespDTO saved = new KamiConfigRespDTO();
        saved.setId(42L);
        when(configMapper.selectList(any())).thenReturn(List.of());
        when(kamiConfigService.createOrUpdateConfig(any())).thenReturn(ResultObject.success(saved));

        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("sourceId", 7);
        sourceConfig.put("aliasName", "shared-warehouse");
        sourceConfig.put("sourceType", "LOCAL");
        Map<String, Object> data = Map.of(
                "kamiConfigs", List.of(sourceConfig),
                "kamiItems", List.of());
        Map<String, Object> context = new LinkedHashMap<>();

        handler.importData(data, context);

        ArgumentCaptor<KamiConfigReqDTO> request = ArgumentCaptor.forClass(KamiConfigReqDTO.class);
        verify(kamiConfigService).createOrUpdateConfig(request.capture());
        assertThat(request.getValue().getId()).isNull();
        verify(configMapper, never()).selectById(7L);
        @SuppressWarnings("unchecked")
        Map<String, Long> sourceIdMap = (Map<String, Long>) context.get("kamiConfigIdMap");
        assertThat(sourceIdMap).containsEntry("7", 42L);
    }
}
