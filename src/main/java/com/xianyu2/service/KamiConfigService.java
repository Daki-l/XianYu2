package com.xianyu2.service;

import com.xianyu2.common.ResultObject;
import com.xianyu2.controller.dto.*;
import com.xianyu2.entity.XianyuKamiConfig;
import com.xianyu2.entity.XianyuKamiItem;

import java.util.List;

public interface KamiConfigService {

    ResultObject<KamiConfigRespDTO> createOrUpdateConfig(KamiConfigReqDTO reqDTO);

    ResultObject<List<KamiConfigRespDTO>> getConfigs();

    ResultObject<KamiConfigRespDTO> getConfigById(Long id);

    ResultObject<Void> deleteConfig(Long id);

    ResultObject<KamiItemRespDTO> addKamiItem(KamiItemReqDTO reqDTO);

    ResultObject<Integer> batchImportKamiItems(KamiBatchImportReqDTO reqDTO);

    ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigId(Long kamiConfigId);

    ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigIdWithFilter(KamiItemQueryReqDTO reqDTO);

    ResultObject<Void> deleteKamiItem(Long id);

    ResultObject<Void> resetKamiItem(Long id);

    XianyuKamiItem acquireKami(Long kamiConfigId, Long accountId, String orderId);

    List<XianyuKamiItem> reserveKami(Long kamiConfigId, Long accountId, String orderId, int quantity);

    void commitReservation(Long accountId, String orderId, String xyGoodsId, String buyerUserId, String buyerUserName);

    void releaseReservation(Long accountId, String orderId);

    void markReservationReviewRequired(Long accountId, String orderId);

    XianyuKamiConfig getConfig(Long kamiConfigId);

    ResultObject<List<KamiItemRespDTO>> exportKamiItems(KamiExportReqDTO reqDTO);
}
