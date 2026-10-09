package com.xianyu2.service.impl;

import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.entity.XianyuKamiConfig;
import com.xianyu2.entity.XianyuKamiItem;
import com.xianyu2.entity.XianyuKamiUsageRecord;
import com.xianyu2.enums.KamiStatus;
import com.xianyu2.exception.BusinessException;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.mapper.XianyuKamiConfigMapper;
import com.xianyu2.mapper.XianyuKamiExternalRequestMapper;
import com.xianyu2.mapper.XianyuKamiItemMapper;
import com.xianyu2.mapper.XianyuKamiUsageRecordMapper;
import com.xianyu2.service.EmailNotifyService;
import com.xianyu2.service.ExternalKamiProvisionService;
import com.xianyu2.service.NotificationCenterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class KamiConfigServiceImplSharedWarehouseTest {

    @Mock
    private XianyuKamiConfigMapper configMapper;
    @Mock
    private XianyuKamiExternalRequestMapper externalRequestMapper;
    @Mock
    private XianyuAccountMapper accountMapper;
    @Mock
    private XianyuKamiItemMapper itemMapper;
    @Mock
    private XianyuKamiUsageRecordMapper usageRecordMapper;
    @Mock
    private EmailNotifyService emailNotifyService;
    @Mock
    private ExternalKamiProvisionService externalKamiProvisionService;
    @Mock
    private NotificationCenterService notificationCenterService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private KamiConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KamiConfigServiceImpl();
        ReflectionTestUtils.setField(service, "kamiConfigMapper", configMapper);
        ReflectionTestUtils.setField(service, "kamiExternalRequestMapper", externalRequestMapper);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "kamiItemMapper", itemMapper);
        ReflectionTestUtils.setField(service, "kamiUsageRecordMapper", usageRecordMapper);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        ReflectionTestUtils.setField(service, "externalKamiProvisionService", externalKamiProvisionService);
        ReflectionTestUtils.setField(service, "notificationCenterService", notificationCenterService);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        lenient().when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    @Test
    void reservesDifferentItemsForDifferentAccountsUsingTheSamePlatformOrderId() {
        XianyuKamiConfig config = localConfig(20L);
        XianyuKamiItem first = availableItem(101L);
        XianyuKamiItem second = availableItem(102L);
        when(accountMapper.selectById(anyLong())).thenReturn(new XianyuAccount());
        when(configMapper.selectById(20L)).thenReturn(config);
        when(configMapper.lockById(20L)).thenReturn(config);
        when(itemMapper.lockReservedByOrder(anyLong(), eq("same-order"))).thenReturn(List.of());
        when(itemMapper.lockAvailable(20L, 1)).thenReturn(List.of(first), List.of(second));
        when(itemMapper.reserve(any(), anyLong(), eq("same-order"))).thenReturn(1);

        service.reserveKami(20L, 1L, "same-order", 1);
        service.reserveKami(20L, 2L, "same-order", 1);

        verify(itemMapper).reserve(List.of(101L), 1L, "same-order");
        verify(itemMapper).reserve(List.of(102L), 2L, "same-order");
        assertThat(first.getOrderAccountId()).isEqualTo(1L);
        assertThat(second.getOrderAccountId()).isEqualTo(2L);
    }

    @Test
    void retriesForTheSameAccountReuseTheExistingReservation() {
        XianyuKamiConfig config = localConfig(20L);
        XianyuKamiItem existing = availableItem(101L);
        existing.setStatus(KamiStatus.RESERVED.getCode());
        existing.setOrderAccountId(1L);
        existing.setOrderId("same-order");
        when(accountMapper.selectById(1L)).thenReturn(new XianyuAccount());
        when(configMapper.selectById(20L)).thenReturn(config);
        when(itemMapper.lockReservedByOrder(1L, "same-order")).thenReturn(List.of(existing));

        List<XianyuKamiItem> result = service.reserveKami(20L, 1L, "same-order", 1);

        assertThat(result).containsExactly(existing);
        verify(itemMapper, never()).lockAvailable(anyLong(), any(Integer.class));
        verify(itemMapper, never()).reserve(any(), anyLong(), any());
    }

    @Test
    void rejectsWarehouseFromAnotherTenantEvenWithoutTenantContext() {
        XianyuAccount account = new XianyuAccount();
        account.setTenantId(1L);
        XianyuKamiConfig config = localConfig(20L);
        config.setTenantId(2L);
        when(accountMapper.selectById(1L)).thenReturn(account);
        when(configMapper.selectById(20L)).thenReturn(config);

        assertThatThrownBy(() -> service.reserveKami(20L, 1L, "same-order", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问");

        verify(itemMapper, never()).lockReservedByOrder(anyLong(), any());
        verify(externalKamiProvisionService, never()).reserve(any(), anyLong(), any(), any(Integer.class));
    }

    @Test
    void commitsAndChangesStateOnlyForTheActualOrderAccount() {
        XianyuKamiItem item = availableItem(101L);
        item.setStatus(KamiStatus.RESERVED.getCode());
        item.setKamiConfigId(20L);
        when(itemMapper.findByOrderAndStatus(1L, "same-order", KamiStatus.RESERVED.getCode()))
                .thenReturn(List.of(item));
        when(itemMapper.commitReservation(1L, "same-order")).thenReturn(1);
        when(itemMapper.countByConfigId(20L)).thenReturn(1);
        when(itemMapper.countUsed(20L)).thenReturn(1);

        service.commitReservation(1L, "same-order", "goods-1", "buyer-1", "Buyer");
        service.releaseReservation(2L, "same-order");
        service.markReservationReviewRequired(2L, "same-order");

        ArgumentCaptor<XianyuKamiUsageRecord> usage = ArgumentCaptor.forClass(XianyuKamiUsageRecord.class);
        verify(usageRecordMapper).insert(usage.capture());
        assertThat(usage.getValue().getXianyuAccountId()).isEqualTo(1L);
        assertThat(usage.getValue().getOrderId()).isEqualTo("same-order");
        verify(itemMapper).commitReservation(1L, "same-order");
        verify(itemMapper, never()).commitReservation(2L, "same-order");
        verify(itemMapper).releaseReservation(2L, "same-order");
        verify(itemMapper).markReservationReviewRequired(2L, "same-order");
    }

    private XianyuKamiConfig localConfig(Long id) {
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setId(id);
        config.setSourceType("LOCAL");
        return config;
    }

    private XianyuKamiItem availableItem(Long id) {
        XianyuKamiItem item = new XianyuKamiItem();
        item.setId(id);
        item.setKamiConfigId(20L);
        item.setStatus(KamiStatus.AVAILABLE.getCode());
        item.setKamiContent("card-" + id);
        return item;
    }
}
