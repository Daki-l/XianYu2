package com.xianyu2.controller;

import com.xianyu2.common.ResultObject;
import com.xianyu2.controller.dto.DeleteAccountReqDTO;
import com.xianyu2.controller.dto.DeleteAccountRespDTO;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.service.AccountService;
import com.xianyu2.service.OperationLogService;
import com.xianyu2.service.WebSocketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private XianyuAccountMapper accountMapper;

    @Mock
    private AccountService accountService;

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private WebSocketService webSocketService;

    @InjectMocks
    private AccountController controller;

    @Test
    void stopsRuntimeConnectionBeforeDeletingAccountData() {
        DeleteAccountReqDTO request = new DeleteAccountReqDTO();
        request.setAccountId(3L);
        when(accountMapper.selectById(3L)).thenReturn(new XianyuAccount());

        ResultObject<DeleteAccountRespDTO> result = controller.deleteAccount(request);

        assertEquals(200, result.getCode());
        InOrder order = inOrder(webSocketService, accountService);
        order.verify(webSocketService).removeAccount(3L);
        order.verify(accountService).deleteAccountAndRelatedData(3L);
        verifyNoMoreInteractions(webSocketService, accountService);
    }

    @Test
    void restoresConnectionWhenDeletingAccountDataFails() {
        DeleteAccountReqDTO request = new DeleteAccountReqDTO();
        request.setAccountId(3L);
        XianyuAccount account = new XianyuAccount();
        when(accountMapper.selectById(3L)).thenReturn(account, account);
        doThrow(new RuntimeException("database failure"))
                .when(accountService).deleteAccountAndRelatedData(3L);

        ResultObject<DeleteAccountRespDTO> result = controller.deleteAccount(request);

        assertEquals(500, result.getCode());
        InOrder order = inOrder(webSocketService, accountService);
        order.verify(webSocketService).removeAccount(3L);
        order.verify(accountService).deleteAccountAndRelatedData(3L);
        order.verify(webSocketService).startWebSocket(3L);
        verify(webSocketService).startWebSocket(3L);
    }
}
