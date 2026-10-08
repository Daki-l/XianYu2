package com.xianyu2.controller;

import com.xianyu2.common.ResultObject;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.controller.dto.AccountReqDTO;
import com.xianyu2.controller.dto.AddAccountRespDTO;
import com.xianyu2.controller.dto.DeleteAccountReqDTO;
import com.xianyu2.controller.dto.DeleteAccountRespDTO;
import com.xianyu2.controller.dto.GetAccountDetailReqDTO;
import com.xianyu2.controller.dto.GetAccountDetailRespDTO;
import com.xianyu2.controller.dto.GetAccountListRespDTO;
import com.xianyu2.controller.dto.ManualAddAccountReqDTO;
import com.xianyu2.controller.dto.UpdateAccountReqDTO;
import com.xianyu2.controller.dto.UpdateAccountRespDTO;
import com.xianyu2.service.AccountService;
import com.xianyu2.service.OperationLogService;
import com.xianyu2.service.WebSocketService;
import com.xianyu2.constants.OperationConstants;
import com.xianyu2.utils.XianyuSignUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 账号管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/account")
public class AccountController {

    @Autowired
    private XianyuAccountMapper accountMapper;
    
    @Autowired
    private AccountService accountService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private WebSocketService webSocketService;

    /**
     * 获取账号列表
     */
    @PostMapping("/list")
    public ResultObject<GetAccountListRespDTO> getAccountList() {
        try {
            List<XianyuAccount> accounts = accountMapper.selectList(null);
            GetAccountListRespDTO respDTO = new GetAccountListRespDTO();
            respDTO.setAccounts(accounts);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取账号列表失败", e);
            return ResultObject.failed("获取账号列表失败: " + e.getMessage());
        }
    }

    /**
     * 添加账号
     */
    @PostMapping("/add")
    public ResultObject<AddAccountRespDTO> addAccount(@RequestBody AccountReqDTO reqDTO) {
        try {
            log.info("添加账号请求: accountNote={}", reqDTO.getAccountNote());
            
            if (reqDTO.getCookie() == null || reqDTO.getCookie().isEmpty()) {
                return ResultObject.failed("Cookie不能为空");
            }
            
            Long accountId = accountService.saveAccountAndCookie(
                    reqDTO.getAccountNote(),
                    reqDTO.getUnb(),
                    reqDTO.getCookie()
            );
            
            AddAccountRespDTO respDTO = new AddAccountRespDTO();
            respDTO.setAccountId(accountId);
            respDTO.setMessage("添加成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("添加账号失败", e);
            return ResultObject.failed("添加账号失败: " + e.getMessage());
        }
    }

    /**
     * 手动添加账号
     */
    @PostMapping("/manualAdd")
    public ResultObject<AddAccountRespDTO> manualAddAccount(@RequestBody ManualAddAccountReqDTO reqDTO) {
        try {
            log.info("手动添加账号请求: accountNote={}", reqDTO.getAccountNote());
            
            if (reqDTO.getCookie() == null || reqDTO.getCookie().isEmpty()) {
                return ResultObject.failed("Cookie不能为空");
            }
            
            // 同时兼容Cookie中的unb和havana登录账号标识。
            String unb = XianyuSignUtils.extractUserId(reqDTO.getCookie());
            if (unb == null || unb.isEmpty()) {
                return ResultObject.failed("无法从Cookie中识别账号信息，请确认包含unb或有效的havana_lgc2字段");
            }
            String normalizedCookie = XianyuSignUtils.normalizeCookieUserId(
                    reqDTO.getCookie(), unb);
            
            // 检查账号是否已存在
            Long existingAccountId = accountService.getAccountIdByUnb(unb);
            if (existingAccountId != null) {
                return ResultObject.failed("账号已存在");
            }
            
            // 保存账号和Cookie信息
            Long accountId = accountService.saveAccountAndCookie(
                    reqDTO.getAccountNote(),
                    unb,
                    normalizedCookie
            );
            
            AddAccountRespDTO respDTO = new AddAccountRespDTO();
            respDTO.setAccountId(accountId);
            respDTO.setMessage("添加成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("手动添加账号失败", e);
            return ResultObject.failed("添加账号失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新账号
     */
    @PostMapping("/update")
    public ResultObject<UpdateAccountRespDTO> updateAccount(@RequestBody UpdateAccountReqDTO reqDTO) {
        try {
            log.info("更新账号请求: accountId={}", reqDTO.getAccountId());
            
            if (reqDTO.getAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            XianyuAccount account = accountMapper.selectById(reqDTO.getAccountId());
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            
            Integer previousRateDetailsEnabled = account.getMerchantRateDetailsEnabled();

            // 只允许更新账号备注和本次明确提供的账号级评价详情开关
            if (reqDTO.getAccountNote() != null) {
                account.setAccountNote(reqDTO.getAccountNote());
            }
            if (reqDTO.getMerchantRateDetailsEnabled() != null) {
                Integer enabled = reqDTO.getMerchantRateDetailsEnabled();
                if (enabled != 0 && enabled != 1) {
                    return ResultObject.failed("评价详情同步开关只能是0或1");
                }
                account.setMerchantRateDetailsEnabled(enabled);
            }

            accountMapper.updateById(account);

            if (reqDTO.getMerchantRateDetailsEnabled() != null
                    && !java.util.Objects.equals(previousRateDetailsEnabled, account.getMerchantRateDetailsEnabled())) {
                operationLogService.log(account.getId(), OperationConstants.Type.UPDATE,
                        OperationConstants.Module.ACCOUNT,
                        "评价详情同步开关已" + (Integer.valueOf(1).equals(account.getMerchantRateDetailsEnabled()) ? "开启" : "关闭"),
                        OperationConstants.Status.SUCCESS,
                        OperationConstants.TargetType.ACCOUNT, String.valueOf(account.getId()),
                        String.valueOf(reqDTO.getMerchantRateDetailsEnabled()), null, null, null);
            }
            
            // 不再更新Cookie和UNB
            
            UpdateAccountRespDTO respDTO = new UpdateAccountRespDTO();
            respDTO.setMessage("更新成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("更新账号失败", e);
            return ResultObject.failed("更新账号失败: " + e.getMessage());
        }
    }

    /**
     * 删除账号
     */
    @PostMapping("/delete")
    public ResultObject<DeleteAccountRespDTO> deleteAccount(@RequestBody DeleteAccountReqDTO reqDTO) {
        try {
            Long id = reqDTO.getAccountId();
            log.info("删除账号请求: accountId={}", id);
            
            XianyuAccount account = accountMapper.selectById(id);
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            
            // 先终止该账号的运行时连接和后台任务，避免已删除账号继续接收消息或重连。
            webSocketService.removeAccount(id);

            // 删除账号关联的所有数据
            accountService.deleteAccountAndRelatedData(id);
            
            DeleteAccountRespDTO respDTO = new DeleteAccountRespDTO();
            respDTO.setMessage("删除成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            // 删除事务回滚时账号仍然存在，恢复先前停止的连接，避免账号被意外留在离线状态。
            if (reqDTO.getAccountId() != null && accountMapper.selectById(reqDTO.getAccountId()) != null) {
                webSocketService.startWebSocket(reqDTO.getAccountId());
            }
            log.error("删除账号失败", e);
            return ResultObject.failed("删除账号失败: " + e.getMessage());
        }
    }

    /**
     * 获取账号详情
     */
    @PostMapping("/detail")
    public ResultObject<GetAccountDetailRespDTO> getAccountDetail(@RequestBody GetAccountDetailReqDTO reqDTO) {
        try {
            Long id = reqDTO.getAccountId();
            XianyuAccount account = accountMapper.selectById(id);
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            GetAccountDetailRespDTO respDTO = new GetAccountDetailRespDTO();
            respDTO.setAccount(account);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取账号详情失败", e);
            return ResultObject.failed("获取账号详情失败: " + e.getMessage());
        }
    }



}
