package com.xianyu2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuestMtopTokenServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_350_620_000L), ZoneOffset.UTC);
    private MockWebServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void retriesOnceAfterMtopProvidesGuestTokenAndSendsItOnTheRetry() throws Exception {
        startServer();
        server.enqueue(tokenEmptyResponse().addHeader("Set-Cookie", "_m_h5_tk=guest-token_1899430620000; Path=/"));
        server.enqueue(successResponse());
        GuestMtopTokenService service = service(() -> 101L);

        String response = service.search(Map.of("keyword", "测试商品"));

        RecordedRequest first = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest second = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{}}", response);
        assertEquals("/h5/mtop.taobao.idlemtopsearch.pc.search/1.0/", first.getRequestUrl().encodedPath());
        assertEquals("mtop.taobao.idlemtopsearch.pc.search", first.getRequestUrl().queryParameter("api"));
        // 首请求应预置设备标识cna（防平台风控拦截），但不携带令牌
        String firstCookie = first.getHeader("Cookie");
        assertTrue(firstCookie != null && firstCookie.matches(".*(^|; )cna=[A-Za-z0-9]{22}(;|$).*"));
        assertFalse(firstCookie.contains("_m_h5_tk"));
        assertTrue(second.getHeader("Cookie").contains("_m_h5_tk=guest-token_1899430620000"));
        assertTrue(second.getHeader("Cookie").contains("cna="));
        assertNotEquals(first.getRequestUrl().queryParameter("sign"), second.getRequestUrl().queryParameter("sign"));
    }

    @Test
    void tokenErrorWithoutNewCookieIsNotRetried() throws Exception {
        startServer();
        server.enqueue(tokenEmptyResponse());
        GuestMtopTokenService service = service(() -> 101L);

        assertThrows(IllegalStateException.class, () -> service.search(Map.of("keyword", "测试商品")));

        assertEquals(1, server.getRequestCount());
        assertEquals("mtop.taobao.idlemtopsearch.pc.search", server.takeRequest().getRequestUrl().queryParameter("api"));
    }

    @Test
    void rateLimitCooldownIsScopedToTheTenantSession() throws Exception {
        startServer();
        server.enqueue(new MockResponse().setBody("{\"ret\":[\"RGV587_ERROR::SM::被挤爆啦\"]}"));
        server.enqueue(successResponse());
        AtomicInteger tenantId = new AtomicInteger(101);
        GuestMtopTokenService service = service(() -> (long) tenantId.get());

        assertThrows(GuestMtopTokenService.GuestReadUnavailableException.class,
                () -> service.itemDetail("12345678"));
        tenantId.set(202);
        assertEquals("{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{}}",
                service.itemDetail("12345678"));

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void oneTenantSlowRequestDoesNotBlockAnotherTenantSession() throws Exception {
        startServer();
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        CountDownLatch secondRequestReceived = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (requestCount.incrementAndGet() == 1) {
                    firstRequestStarted.countDown();
                    try {
                        releaseFirstRequest.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    secondRequestReceived.countDown();
                }
                return successResponse();
            }
        });
        ThreadLocal<Long> tenantId = new ThreadLocal<>();
        GuestMtopTokenService service = service(tenantId::get);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> searchAsTenant(service, tenantId, 101L));
            assertTrue(firstRequestStarted.await(1, TimeUnit.SECONDS));
            Future<String> second = executor.submit(() -> searchAsTenant(service, tenantId, 202L));

            assertTrue(secondRequestReceived.await(1, TimeUnit.SECONDS));
            releaseFirstRequest.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS).contains("SUCCESS"));
            assertTrue(second.get(2, TimeUnit.SECONDS).contains("SUCCESS"));
        } finally {
            releaseFirstRequest.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void searchRequestKeepsThePublicSearchFilterShape() {
        Map<String, Object> request = PlatformPublishService.buildSearchRequest("  显卡  ", 2, 30);

        assertEquals("显卡", request.get("keyword"));
        assertEquals(2, request.get("pageNumber"));
        assertEquals(Map.of("searchFilter", ""), request.get("propValueStr"));
    }

    @Test
    void springCanCreateTheGuestSessionBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class);
            context.register(GuestMtopTokenService.class);
            context.refresh();

            assertNotNull(context.getBean(GuestMtopTokenService.class));
        }
    }

    private void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    private GuestMtopTokenService service(java.util.function.Supplier<Long> tenantIdSupplier) {
        HttpUrl baseUrl = server.url("/h5/");
        return new GuestMtopTokenService(new ObjectMapper(), baseUrl, CLOCK, tenantIdSupplier);
    }

    private String searchAsTenant(GuestMtopTokenService service, ThreadLocal<Long> tenantId, long id) {
        tenantId.set(id);
        try {
            return service.search(Map.of("keyword", "测试商品"));
        } finally {
            tenantId.remove();
        }
    }

    private static MockResponse tokenEmptyResponse() {
        return new MockResponse().setBody("{\"ret\":[\"FAIL_SYS_TOKEN_EMPTY::令牌为空\"]}");
    }

    private static MockResponse successResponse() {
        return new MockResponse().setBody("{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{}}");
    }
}
