package com.xianyu2.mapper;

import com.xianyu2.entity.XianyuChatMessage;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class XianyuChatMessageMapperSqlContractTest {

    @Test
    void platformHistoryUpsertRefreshesThePlatformFingerprint() throws NoSuchMethodException {
        Insert insert = XianyuChatMessageMapper.class
                .getMethod("upsertPlatformHistory", XianyuChatMessage.class)
                .getAnnotation(Insert.class);

        String sql = String.join(" ", insert.value()).replaceAll("\\s+", " ");

        assertTrue(sql.contains("dedupe_fingerprint = IF(message_source = 'PLATFORM', "
                + "VALUES(dedupe_fingerprint), dedupe_fingerprint)"));
    }
}
