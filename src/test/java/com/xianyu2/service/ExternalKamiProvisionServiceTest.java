package com.xianyu2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.entity.XianyuKamiConfig;
import com.xianyu2.entity.XianyuKamiExternalRequest;
import com.xianyu2.entity.XianyuKamiItem;
import com.xianyu2.enums.KamiStatus;
import com.xianyu2.mapper.XianyuKamiConfigMapper;
import com.xianyu2.mapper.XianyuKamiExternalRequestMapper;
import com.xianyu2.mapper.XianyuKamiItemMapper;
import com.xianyu2.service.kami.ExternalKamiGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalKamiProvisionServiceTest {

    @Mock
    private XianyuKamiExternalRequestMapper requestMapper;
    @Mock
    private XianyuKamiItemMapper itemMapper;
    @Mock
    private XianyuKamiConfigMapper configMapper;
    @Mock
    private ExternalKamiGateway gateway;
    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void persistsSupplierOrderIdAgainstTheActualAccountScopedRequest() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        ExternalKamiProvisionService service = new ExternalKamiProvisionService(
                requestMapper, itemMapper, configMapper, gateway, new ObjectMapper(), transactionManager);
        XianyuKamiConfig config = apiConfig();
        XianyuKamiItem reserved = new XianyuKamiItem();
        reserved.setId(301L);
        AtomicReference<XianyuKamiExternalRequest> requestRef = new AtomicReference<>();
        when(itemMapper.lockReservedByOrder(12L, "same-order")).thenReturn(List.of());
        when(configMapper.lockById(20L)).thenReturn(config);
        when(requestMapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            XianyuKamiExternalRequest request = invocation.getArgument(0);
            request.setId(99L);
            requestRef.set(request);
            return 1;
        });
        when(requestMapper.findByOrder(7L, 20L, 12L, "same-order"))
                .thenAnswer(invocation -> requestRef.get());
        when(gateway.request(eq(config), eq(12L), eq("same-order"), eq(1), anyString()))
                .thenReturn("{\"data\":{\"cards\":[\"card-A\"],\"supplierOrder\":\"supplier-9\"}}");
        when(requestMapper.markSuccess(99L, "supplier-9", "received 1 item(s)")).thenReturn(1);
        when(itemMapper.findByConfigAndOrderAndStatus(
                20L, 12L, "same-order", KamiStatus.RESERVED.getCode()))
                .thenReturn(List.of(reserved));

        List<XianyuKamiItem> result = service.reserve(config, 12L, "same-order", 1);

        assertThat(result).containsExactly(reserved);
        assertThat(requestRef.get().getXianyuAccountId()).isEqualTo(12L);
        assertThat(requestRef.get().getOrderId()).isEqualTo("same-order");
        ArgumentCaptor<XianyuKamiItem> item = ArgumentCaptor.forClass(XianyuKamiItem.class);
        verify(itemMapper).insert(item.capture());
        assertThat(item.getValue().getOrderAccountId()).isEqualTo(12L);
        assertThat(item.getValue().getOrderId()).isEqualTo("same-order");
        verify(requestMapper).markSuccess(99L, "supplier-9", "received 1 item(s)");
    }

    @Test
    void createsSeparateRequestsForDifferentAccountsWithTheSameOrderId() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        ExternalKamiProvisionService service = new ExternalKamiProvisionService(
                requestMapper, itemMapper, configMapper, gateway, new ObjectMapper(), transactionManager);
        XianyuKamiConfig config = apiConfig();
        Map<Long, XianyuKamiExternalRequest> requestsByAccount = new LinkedHashMap<>();
        when(itemMapper.lockReservedByOrder(anyLong(), eq("same-order"))).thenReturn(List.of());
        when(configMapper.lockById(20L)).thenReturn(config);
        when(requestMapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            XianyuKamiExternalRequest request = invocation.getArgument(0);
            request.setId(100L + request.getXianyuAccountId());
            requestsByAccount.put(request.getXianyuAccountId(), request);
            return 1;
        });
        when(requestMapper.findByOrder(eq(7L), eq(20L), anyLong(), eq("same-order")))
                .thenAnswer(invocation -> requestsByAccount.get(invocation.getArgument(2)));
        when(gateway.request(eq(config), anyLong(), eq("same-order"), eq(1), anyString()))
                .thenReturn("{\"data\":{\"cards\":[\"card-A\"],\"supplierOrder\":\"supplier-9\"}}");
        when(requestMapper.markSuccess(anyLong(), eq("supplier-9"), eq("received 1 item(s)"))).thenReturn(1);
        when(itemMapper.findByConfigAndOrderAndStatus(
                eq(20L), anyLong(), eq("same-order"), eq(KamiStatus.RESERVED.getCode())))
                .thenReturn(List.of(new XianyuKamiItem()));

        service.reserve(config, 12L, "same-order", 1);
        service.reserve(config, 13L, "same-order", 1);

        assertThat(requestsByAccount.keySet()).containsExactly(12L, 13L);
        assertThat(requestsByAccount.values())
                .extracting(XianyuKamiExternalRequest::getOrderId)
                .containsOnly("same-order");
    }

    private XianyuKamiConfig apiConfig() {
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setId(20L);
        config.setTenantId(7L);
        config.setSourceType("API");
        config.setExternalApiResultPath("data.cards");
        config.setExternalApiOrderIdPath("data.supplierOrder");
        return config;
    }
}
