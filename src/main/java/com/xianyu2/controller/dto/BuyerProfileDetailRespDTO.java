package com.xianyu2.controller.dto;

import com.xianyu2.entity.XianyuGoodsOrder;
import lombok.Data;

import java.util.List;

/**
 * 买家全链路详情
 */
@Data
public class BuyerProfileDetailRespDTO {

    private BuyerProfileRespDTO profile;

    private List<XianyuGoodsOrder> orders;

    private List<BuyerMessageDTO> messages;

    private List<BuyerRelatedGoodsDTO> goods;
}
