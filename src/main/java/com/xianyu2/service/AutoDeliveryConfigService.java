package com.xianyu2.service;

import com.xianyu2.common.ResultObject;
import com.xianyu2.controller.dto.AutoDeliveryConfigReqDTO;
import com.xianyu2.controller.dto.AutoDeliveryConfigRespDTO;
import com.xianyu2.controller.dto.AutoDeliveryConfigQueryReqDTO;

import java.util.List;

public interface AutoDeliveryConfigService {
    
    ResultObject<AutoDeliveryConfigRespDTO> saveOrUpdateConfig(AutoDeliveryConfigReqDTO reqDTO);
    
    ResultObject<AutoDeliveryConfigRespDTO> getConfig(AutoDeliveryConfigQueryReqDTO reqDTO);
    
    ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByGoodsId(Long xianyuAccountId, String xyGoodsId);
    
    ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId);
    
    ResultObject<Void> deleteConfig(Long xianyuAccountId, String xyGoodsId);
}
