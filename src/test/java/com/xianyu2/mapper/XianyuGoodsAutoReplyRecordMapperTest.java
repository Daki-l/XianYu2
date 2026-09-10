package com.xianyu2.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XianyuGoodsAutoReplyRecordMapperTest {

    @Test
    void dueScanAndClaimOnlyAcceptPendingTasks() throws Exception {
        String findDueSql = XianyuGoodsAutoReplyRecordMapper.class
                .getMethod("findDue", int.class)
                .getAnnotation(Select.class)
                .value()[0];
        String claimSql = XianyuGoodsAutoReplyRecordMapper.class
                .getMethod("claim", Long.class, String.class, int.class)
                .getAnnotation(Update.class)
                .value()[0];

        assertTrue(findDueSql.contains("state = 0"));
        assertFalse(findDueSql.contains("state = 2"));
        assertTrue(claimSql.contains("WHERE id = #{id} AND state = 0"));
        assertFalse(claimSql.contains("lease_expire_time < NOW"));
    }

    @Test
    void timelineIncludesAllActiveStatesButBoundsTerminalStates() throws Exception {
        String timelineSql = XianyuGoodsAutoReplyRecordMapper.class
                .getMethod("findTimelineStates", Long.class, String.class)
                .getAnnotation(Select.class)
                .value()[0];

        assertTrue(timelineSql.contains("state IN (0, 2)"));
        assertTrue(timelineSql.contains("state IN (-1, -2)"));
        assertTrue(timelineSql.contains("LIMIT 20"));
    }
}
