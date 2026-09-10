package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 更新账号请求DTO
 */
@Data
public class UpdateAccountReqDTO {
    private Long accountId;       // 账号ID
    private String accountNote;   // 账号备注
    private Integer merchantRateDetailsEnabled; // 评价详情同步开关，null表示不修改
}
