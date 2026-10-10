package com.xianyu2.controller.dto;

import lombok.Data;

/**
 * 强制结束人工接管请求。
 */
@Data
public class EndHumanTakeoverReqDTO {

    private Long xianyuAccountId;

    private String sid;
}
