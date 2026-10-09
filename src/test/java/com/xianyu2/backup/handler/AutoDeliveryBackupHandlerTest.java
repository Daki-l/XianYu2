package com.xianyu2.backup.handler;

import com.xianyu2.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyu2.mapper.XianyuKamiConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AutoDeliveryBackupHandlerTest {

    @Mock
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    @Mock
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Test
    void doesNotTreatAnUnmappedSourceIdAsALocalWarehouseId() {
        AutoDeliveryBackupHandler handler = new AutoDeliveryBackupHandler();
        ReflectionTestUtils.setField(handler, "autoDeliveryConfigMapper", autoDeliveryConfigMapper);
        ReflectionTestUtils.setField(handler, "kamiConfigMapper", kamiConfigMapper);

        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("unb", "source-account");
        sourceConfig.put("xyGoodsId", "goods-1");
        sourceConfig.put("deliveryMode", 2);
        sourceConfig.put("kamiConfigSourceIds", "7");
        Map<String, Object> data = Map.of("autoDeliveryConfigs", List.of(sourceConfig));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("source-account", 1L));
        context.put("kamiConfigIdMap", Map.of());

        handler.importData(data, context);

        verify(kamiConfigMapper, never()).selectById(anyLong());
        verify(autoDeliveryConfigMapper, never()).insert(any());
        verify(autoDeliveryConfigMapper, never()).updateById(any());
    }
}
