package com.xianyu2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.context.UserContext;
import com.xianyu2.entity.MerchantResource;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.mapper.MerchantDistributionMapper;
import com.xianyu2.mapper.MerchantResourceMapper;
import com.xianyu2.mapper.MerchantShortLinkMapper;
import com.xianyu2.mapper.MerchantTaskMapper;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyu2.mapper.XianyuGoodsOrderMapper;
import com.xianyu2.mapper.XianyuKamiConfigMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantOperationsServiceTest {

    private MerchantOperationsService operationsService;
    private MerchantResourceMapper resourceMapper;
    private XianyuAccountMapper accountMapper;

    @BeforeEach
    void setUp() {
        resourceMapper = mock(MerchantResourceMapper.class);
        accountMapper = mock(XianyuAccountMapper.class);
        operationsService = new MerchantOperationsService(
                resourceMapper,
                mock(MerchantTaskMapper.class),
                mock(MerchantDistributionMapper.class),
                accountMapper,
                mock(XianyuKamiConfigMapper.class),
                mock(MerchantShortLinkMapper.class),
                mock(XianyuGoodsAutoDeliveryConfigMapper.class),
                mock(XianyuGoodsOrderMapper.class),
                mock(ItemService.class),
                mock(OrderService.class),
                mock(RiskControlService.class),
                mock(PlatformPublishService.class),
                mock(OpportunityAnalysisService.class),
                mock(WorkflowDefinitionService.class),
                mock(OperationLogService.class),
                mock(AIService.class),
                mock(OpportunityImageService.class),
                new ObjectMapper());
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void searchRequiresAnAccount() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> operationsService.searchOpportunities(Map.of("keyword", "显卡")));

        assertEquals("请选择用于搜索的账号", error.getMessage());
    }

    @Test
    void sellerProfileRequiresAnAccount() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> operationsService.getSellerPublicProfile(Map.of("itemId", "12345678")));

        assertEquals("请选择用于查看卖家口碑的账号", error.getMessage());
    }

    @Test
    void shopCrawlRequiresAnAccount() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> operationsService.crawlShopOpportunities(Map.of(
                        "shopUrl", "https://www.goofish.com/personal?userId=12345")));

        assertEquals("请选择用于采集的账号", error.getMessage());
    }

    @Test
    void importRequiresAnAccount() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> operationsService.importOpportunities(Map.of(
                        "candidates", List.of(Map.of("title", "测试商品")))));

        assertEquals("请选择用于导入的账号", error.getMessage());
    }

    @Test
    void importAssociatesLegacyUnassignedSupplyWithTheSelectedAccount() {
        UserContext.set(101L, "test-user");
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        when(accountMapper.selectById(7L)).thenReturn(account);

        MerchantResource existing = new MerchantResource();
        existing.setTenantId(101L);
        existing.setResourceType("SUPPLY");
        existing.setXyGoodsId("12345678");
        when(resourceMapper.selectByTenantTypeAndGoodsId(101L, "SUPPLY", "12345678"))
                .thenReturn(existing);

        operationsService.importOpportunities(Map.of(
                "xianyuAccountId", 7L,
                "candidates", List.of(Map.of("itemId", "12345678", "title", "测试商品"))));

        assertEquals(7L, existing.getXianyuAccountId());
        verify(resourceMapper).updateById(existing);
    }
}
