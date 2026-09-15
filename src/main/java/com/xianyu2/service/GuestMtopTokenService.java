package com.xianyu2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.context.TenantContext;
import com.xianyu2.utils.SessionCookieJar;
import com.xianyu2.utils.XianyuSignUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 闲鱼游客只读会话。
 *
 * <p>该会话不读取、不写入账号 Cookie，也不会调用账号风控或写操作。当前只开放经过验证的
 * 商品搜索和商品详情两个公开读取能力；不要把任意 mtop API 暴露为游客调用。</p>
 */
@Slf4j
@Service
public class GuestMtopTokenService {

    private static final String BASE_URL = "https://h5api.m.goofish.com/h5/";
    private static final String APP_KEY = "34839810";
    private static final long RISK_COOLDOWN_MILLIS = 60_000L;
    private static final long SYSTEM_SESSION_KEY = 0L;

    private final ObjectMapper objectMapper;
    private final HttpUrl baseUrl;
    private final Clock clock;
    private final Supplier<Long> tenantIdSupplier;
    private final ConcurrentMap<Long, GuestSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public GuestMtopTokenService(ObjectMapper objectMapper) {
        this(objectMapper, HttpUrl.get(BASE_URL), Clock.systemUTC(), TenantContext::get);
    }

    GuestMtopTokenService(ObjectMapper objectMapper,
                          HttpUrl baseUrl,
                          Clock clock,
                          Supplier<Long> tenantIdSupplier) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.tenantIdSupplier = tenantIdSupplier;
    }

    /** 游客关键词搜索。 */
    public String search(Map<String, Object> requestData) {
        return call(PublicReadOperation.SEARCH, requestData);
    }

    /** 游客商品详情查询。 */
    public String itemDetail(String itemId) {
        if (itemId == null || !itemId.matches("\\d{8,}")) {
            throw new IllegalArgumentException("商品ID格式无效");
        }
        return call(PublicReadOperation.ITEM_DETAIL, Map.of("itemId", itemId));
    }

    private String call(PublicReadOperation operation, Map<String, Object> requestData) {
        GuestSession session = currentSession();
        if (!session.requestPermit.tryAcquire()) {
            throw new GuestReadUnavailableException("游客查询繁忙，请稍后重试");
        }
        try {
            assertAvailable(session);
            String tokenBeforeRequest = session.cookieJar.getMh5tkToken();
            String response = execute(session, operation, requestData);
            if (isTokenError(response)) {
                String refreshedToken = session.cookieJar.getMh5tkToken();
                if (refreshedToken.isBlank() || refreshedToken.equals(tokenBeforeRequest)) {
                    throw new IllegalStateException("游客会话未取得有效令牌，请稍后重试");
                }
                // 仅在 mtop 响应实际下发新令牌后重试一次，避免重复无效请求。
                log.debug("游客会话令牌已刷新，执行一次标准重试: operation={}", operation);
                response = execute(session, operation, requestData);
            }
            if (isRiskLimited(response)) {
                session.cooldownUntil = clock.millis() + RISK_COOLDOWN_MILLIS;
                log.warn("游客只读会话被平台暂时限流，进入 {} 秒冷却: tenantId={}, operation={}",
                        RISK_COOLDOWN_MILLIS / 1000, session.tenantId, operation);
                throw new GuestReadUnavailableException("游客查询暂时不可用，请稍后重试或选择已登录账号");
            }
            if (!isSuccess(response)) {
                throw new IllegalStateException("游客查询失败: " + responseCode(response));
            }
            return response;
        } finally {
            session.requestPermit.release();
        }
    }

    private GuestSession currentSession() {
        Long tenantId = tenantIdSupplier.get();
        long sessionKey = tenantId == null ? SYSTEM_SESSION_KEY : tenantId;
        return sessions.computeIfAbsent(sessionKey, key -> new GuestSession(key, createHttpClient()));
    }

    private OkHttpClient createHttpClient() {
        // 游客请求要在浏览器请求超时前结束，避免服务端继续占用会话。
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    private String execute(GuestSession session, PublicReadOperation operation, Map<String, Object> requestData) {
        try {
            String dataJson = objectMapper.writeValueAsString(requestData);
            String timestamp = String.valueOf(clock.millis());
            String sign = XianyuSignUtils.generateSign(timestamp, session.cookieJar.getMh5tkToken(), dataJson);
            HttpUrl.Builder url = baseUrl.newBuilder()
                    .addPathSegment(operation.apiName)
                    .addPathSegment("1.0")
                    .addPathSegment("")
                    .addQueryParameter("jsv", "2.7.2")
                    .addQueryParameter("appKey", APP_KEY)
                    .addQueryParameter("t", timestamp)
                    .addQueryParameter("sign", sign)
                    .addQueryParameter("v", "1.0")
                    .addQueryParameter("type", "originaljson")
                    .addQueryParameter("accountSite", "xianyu")
                    .addQueryParameter("dataType", "json")
                    .addQueryParameter("timeout", "20000")
                    .addQueryParameter("api", operation.apiName)
                    .addQueryParameter("sessionOption", "AutoLoginOnly");
            operation.extraQueryParameters.forEach(url::addQueryParameter);

            Request request = new Request.Builder()
                    .url(url.build())
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Origin", "https://www.goofish.com")
                    .header("Referer", "https://www.goofish.com/")
                    .post(new FormBody.Builder().add("data", dataJson).build())
                    .build();
            try (Response response = session.httpClient.newCall(request).execute()) {
                return response.body() == null ? "" : response.body().string();
            }
        } catch (IOException e) {
            throw new IllegalStateException("游客会话请求失败，请稍后重试", e);
        } catch (Exception e) {
            throw new IllegalStateException("游客会话请求构建失败", e);
        }
    }

    private void assertAvailable(GuestSession session) {
        long remaining = session.cooldownUntil - clock.millis();
        if (remaining > 0) {
            long seconds = Math.max(1, (remaining + 999) / 1000);
            throw new GuestReadUnavailableException("游客查询暂时不可用，请约 " + seconds + " 秒后重试或选择已登录账号");
        }
    }

    private boolean isTokenError(String response) {
        return response != null && (response.contains("FAIL_SYS_TOKEN_EXPIRED")
                || response.contains("FAIL_SYS_TOKEN_EXOIRED")
                || response.contains("FAIL_SYS_TOKEN_EMPTY"));
    }

    private boolean isRiskLimited(String response) {
        if (response == null) {
            return false;
        }
        String value = response.toUpperCase();
        return value.contains("RGV") || value.contains("SM::")
                || value.contains("FAIL_SYS_TRAFFIC_LIMIT") || response.contains("被挤爆");
    }

    @SuppressWarnings("unchecked")
    private boolean isSuccess(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(response, Map.class);
            Object ret = root.get("ret");
            return ret instanceof List<?> values && !values.isEmpty()
                    && String.valueOf(values.getFirst()).contains("SUCCESS");
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private String responseCode(String response) {
        if (response == null || response.isBlank()) {
            return "响应为空";
        }
        try {
            Map<String, Object> root = objectMapper.readValue(response, Map.class);
            Object ret = root.get("ret");
            if (ret instanceof List<?> values && !values.isEmpty()) {
                return String.valueOf(values.getFirst());
            }
        } catch (Exception ignored) {
            // 返回通用错误，避免把 HTML 或异常正文原样返回给前端。
        }
        return "平台响应异常";
    }

    private enum PublicReadOperation {
        SEARCH("mtop.taobao.idlemtopsearch.pc.search", Map.of(
                "spm_cnt", "a21ybx.search.0.0",
                "spm_pre", "a21ybx.home.searchInput.0")),
        ITEM_DETAIL("mtop.taobao.idle.pc.detail", Map.of(
                "spm_cnt", "a21ybx.im.0.0",
                "spm_pre", "a21ybx.item.want.1"));

        private final String apiName;
        private final Map<String, String> extraQueryParameters;

        PublicReadOperation(String apiName, Map<String, String> extraQueryParameters) {
            this.apiName = apiName;
            this.extraQueryParameters = extraQueryParameters;
        }
    }

    private static final class GuestSession {
        private final long tenantId;
        private final SessionCookieJar cookieJar;
        private final OkHttpClient httpClient;
        private final Semaphore requestPermit = new Semaphore(1);
        private volatile long cooldownUntil;

        private GuestSession(long tenantId, OkHttpClient baseClient) {
            this.tenantId = tenantId;
            // 无cna设备标识的游客请求会被平台风控直接拦截（RGV587），
            // 会话创建时预置随机cna保证首次请求即可通过。
            this.cookieJar = new SessionCookieJar();
            this.cookieJar.putCookie("cna", randomDeviceToken());
            this.httpClient = baseClient.newBuilder().cookieJar(cookieJar).build();
        }
    }

    private static String randomDeviceToken() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder token = new StringBuilder(22);
        for (int i = 0; i < 22; i++) {
            token.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return token.toString();
    }

    static class GuestReadUnavailableException extends IllegalStateException {
        GuestReadUnavailableException(String message) {
            super(message);
        }
    }
}
