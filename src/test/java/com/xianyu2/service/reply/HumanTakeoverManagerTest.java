package com.xianyu2.service.reply;

import com.xianyu2.mapper.XianyuHumanInterventionRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HumanTakeoverManagerTest {

    @Mock
    private XianyuHumanInterventionRecordMapper interventionRecordMapper;

    @Test
    void forceEndRemovesOnlyTheTargetSessionFromTheHotCache() {
        HumanTakeoverManager manager = new HumanTakeoverManager(interventionRecordMapper);
        manager.takeover(1L, "goods-a", "session-a@goofish", 10);
        manager.takeover(1L, "goods-b", "session-b@goofish", 10);
        when(interventionRecordMapper.endActiveByAccountAndSId(1L, "session-a@goofish")).thenReturn(0);

        boolean changed = manager.endTakeover(1L, "session-a@goofish");

        assertFalse(changed);
        assertFalse(manager.isTakenOver(1L, "session-a@goofish"));
        assertTrue(manager.isTakenOver(1L, "session-b@goofish"));
        verify(interventionRecordMapper).endActiveByAccountAndSId(1L, "session-a@goofish");
    }
}
