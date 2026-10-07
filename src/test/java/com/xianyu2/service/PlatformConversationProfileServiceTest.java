package com.xianyu2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.entity.XianyuBuyerProfile;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.mapper.XianyuBuyerProfileMapper;
import com.xianyu2.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformConversationProfileServiceTest {

    @Test
    void doesNotCallPlatformWhenProfileFetchIsDisabled() {
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuBuyerProfileMapper buyerProfileMapper = mock(XianyuBuyerProfileMapper.class);
        AccountService accountService = mock(AccountService.class);
        XianyuApiCallUtils apiCallUtils = mock(XianyuApiCallUtils.class);
        SysSettingService sysSettingService = mock(SysSettingService.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(2L);

        when(accountMapper.selectById(2L)).thenReturn(account);
        when(sysSettingService.getSettingValue("platform_conversation_profile_fetch_enabled"))
                .thenReturn("false");

        PlatformConversationProfileService service = new PlatformConversationProfileService(
                accountMapper, buyerProfileMapper, accountService, apiCallUtils,
                new ObjectMapper(), sysSettingService);

        List<Map<String, Object>> result = service.query(2L, List.of("67793664037@goofish"));

        assertEquals("67793664037@goofish", result.getFirst().get("sid"));
        assertEquals("", result.getFirst().get("nick"));
        verify(buyerProfileMapper).findByBuyer(2L, "67793664037");
        verifyNoInteractions(accountService, apiCallUtils);
    }

    @Test
    void returnsPersistedProfileWhenPlatformFetchIsDisabled() {
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuBuyerProfileMapper buyerProfileMapper = mock(XianyuBuyerProfileMapper.class);
        AccountService accountService = mock(AccountService.class);
        XianyuApiCallUtils apiCallUtils = mock(XianyuApiCallUtils.class);
        SysSettingService sysSettingService = mock(SysSettingService.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(2L);
        XianyuBuyerProfile profile = new XianyuBuyerProfile();
        profile.setBuyerUserName("本地买家");
        profile.setBuyerAvatarUrl("https://example.com/avatar.png");

        when(accountMapper.selectById(2L)).thenReturn(account);
        when(sysSettingService.getSettingValue("platform_conversation_profile_fetch_enabled"))
                .thenReturn("false");
        when(buyerProfileMapper.findByBuyer(2L, "67793664037")).thenReturn(profile);

        PlatformConversationProfileService service = new PlatformConversationProfileService(
                accountMapper, buyerProfileMapper, accountService, apiCallUtils,
                new ObjectMapper(), sysSettingService);

        List<Map<String, Object>> result = service.query(2L, List.of("67793664037@goofish"));

        assertEquals("本地买家", result.getFirst().get("nick"));
        assertEquals("https://example.com/avatar.png", result.getFirst().get("avatar"));
        verifyNoInteractions(accountService, apiCallUtils);
    }
}
