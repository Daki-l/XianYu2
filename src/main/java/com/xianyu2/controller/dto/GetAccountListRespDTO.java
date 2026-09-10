package com.xianyu2.controller.dto;

import com.xianyu2.entity.XianyuAccount;
import lombok.Data;

import java.util.List;

/**
 * 获取账号列表响应DTO
 */
@Data
public class GetAccountListRespDTO {
    private List<XianyuAccount> accounts;       // 账号列表
}