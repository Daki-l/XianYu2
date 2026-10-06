package com.xianyu2.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.service.OperationLogService;
import com.xianyu2.service.RiskControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RiskControlServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void platformTooBusyStartsFixedCooldownAndCanBeManuallyCleared() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-07T00:00:00Z"));
        RiskControlServiceImpl service = new RiskControlServiceImpl(new ObjectMapper(),
                tempDir.resolve("risk-guard.json"), clock, mock(OperationLogService.class));

        service.recordResponse(2L, Map.of("ret", List.of(
                "FAIL_BIZ_interceptor_unknow_err::闲鱼太累了，休息一会吧||doNotDetete")));

        RiskControlService.GuardStatus status = service.getStatus(2L);
        assertEquals(RiskControlService.GuardState.CIRCUIT_OPEN, status.state());
        assertEquals("PLATFORM_TOO_BUSY", status.reason());
        assertEquals(30 * 60, status.remainingSeconds());

        clock.advance(Duration.ofMinutes(1));
        service.recordResponse(2L, Map.of("message", "闲鱼太累了，休息一会吧"));
        assertEquals(29 * 60, service.getStatus(2L).remainingSeconds());

        service.forceClearCircuit(2L);
        assertEquals(RiskControlService.GuardState.NORMAL, service.getStatus(2L).state());
        assertTrue(service.detectRiskControl(Map.of("message", "闲鱼太累了，休息一会吧")));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
