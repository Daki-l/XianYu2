package com.xianyu2.service.reply;

import com.xianyu2.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.xianyu2.service.AutoReplyService;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoReplyDelayServiceImplTest {

    @Mock
    private AutoReplyService autoReplyService;
    @Mock
    private HumanTakeoverManager takeoverManager;
    @Mock
    private ReplyConfigProvider configProvider;
    @Mock
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;

    private AutoReplyDelayServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AutoReplyDelayServiceImpl();
        ReflectionTestUtils.setField(service, "autoReplyService", autoReplyService);
        ReflectionTestUtils.setField(service, "takeoverManager", takeoverManager);
        ReflectionTestUtils.setField(service, "configProvider", configProvider);
        ReflectionTestUtils.setField(service, "autoReplyRecordMapper", autoReplyRecordMapper);
    }

    @Test
    void sellerManualReplyCancelsWaitingAndClaimedTasksForTheSession() {
        when(configProvider.isHumanInterventionEnabled(1L, "goods-1")).thenReturn(true);
        when(configProvider.getInterventionMinutes(1L, "goods-1")).thenReturn(10);

        service.recordSellerManualReply(1L, "goods-1", "session@goofish");

        verify(takeoverManager).takeover(1L, "goods-1", "session@goofish", 10);
        verify(autoReplyRecordMapper).cancelActiveBySession(1L, "session@goofish");
        verify(autoReplyRecordMapper, never()).cancelPendingBySession(1L, "session@goofish");
        verifyNoInteractions(autoReplyService);
    }

    @Test
    void sessionCancellationMapperContractIncludesWaitingAndClaimedStates() throws NoSuchMethodException {
        Method method = XianyuGoodsAutoReplyRecordMapper.class.getMethod(
                "cancelActiveBySession", Long.class, String.class);
        Update update = method.getAnnotation(Update.class);
        String sql = String.join(" ", update.value());

        assertTrue(sql.contains("state = -2"));
        assertTrue(sql.contains("state IN (0, 2)"));
        assertTrue(sql.contains("lease_owner = NULL"));
        assertTrue(sql.contains("lease_expire_time = NULL"));
    }
}
