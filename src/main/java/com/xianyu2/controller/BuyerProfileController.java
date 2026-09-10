package com.xianyu2.controller;

import com.xianyu2.common.ResultObject;
import com.xianyu2.controller.dto.BuyerProfileQueryReqDTO;
import com.xianyu2.controller.dto.BuyerProfileDetailReqDTO;
import com.xianyu2.controller.dto.BuyerProfileDetailRespDTO;
import com.xianyu2.controller.dto.BuyerProfileRespDTO;
import com.xianyu2.controller.dto.BuyerProfileSaveReqDTO;
import com.xianyu2.service.BuyerProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 买家管理接口
 */
@RestController
@RequestMapping("/api/buyers")
public class BuyerProfileController {

    private final BuyerProfileService buyerProfileService;

    public BuyerProfileController(BuyerProfileService buyerProfileService) {
        this.buyerProfileService = buyerProfileService;
    }

    @PostMapping("/list")
    public ResultObject<Map<String, Object>> list(@RequestBody BuyerProfileQueryReqDTO request) {
        try {
            return ResultObject.success(buyerProfileService.list(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/save")
    public ResultObject<BuyerProfileRespDTO> save(@Valid @RequestBody BuyerProfileSaveReqDTO request) {
        try {
            return ResultObject.success(buyerProfileService.save(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @PostMapping("/detail")
    public ResultObject<BuyerProfileDetailRespDTO> detail(
            @Valid @RequestBody BuyerProfileDetailReqDTO request) {
        try {
            return ResultObject.success(buyerProfileService.detail(request));
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }
}
