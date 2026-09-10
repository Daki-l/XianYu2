package com.xianyu2.event;

import com.xianyu2.entity.XianyuGoodsOrder;

/**
 * 发货私聊实际发送成功事件
 */
public record DeliveryMessageSentEvent(XianyuGoodsOrder order) {
}
