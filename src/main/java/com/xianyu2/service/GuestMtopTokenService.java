package com.xianyu2.service;

import com.xianyu2.utils.HttpClientUtils;
import com.xianyu2.utils.XianyuApiUtils;
import com.xianyu2.utils.XianyuSignUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 闲鱼 mtop 游客令牌服务
 *
 * 商机搜索等只读调研场景不依赖账号 Cookie：先向 h5api 网关发起一次空令牌请求，
 * 从响应 Set-Cookie 中领取游客 _m_h5_tk 并缓存，之后按 mtop 标准签名直接调用。
 * 令牌过期（FAIL_SYS_TOKEN_EXPIRED）时自动重新领取并重试一次。
 */
@Slf4j
@Service
public class GuestMtopTokenService {

    private static final String BASE_URL = "https://h5api.m.goofish.com/h5/";
    /** 用于领取游客令牌的轻量接口，与正式调用保持同域 */
    private static final String BOOTSTRAP_API = "mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get";

    private String cachedCookie;
    private long cachedExpireAt;

    /**
     * 以游客身份调用 mtop 接口，返回响应体文本。
     *
     * @param apiName  接口名，例如 mtop.taobao.idlemtopsearch.pc.search
     * @param dataMap  业务参数
     * @param extraQueryParams 附加 URL 参数
     * @return 响应体
     */
    public synchronized String callAsGuest(String apiName, Map<String, Object> dataMap,
                                           Map<String, String> extraQueryParams) {
        String cookie = getGuestCookie(false);
        String body = doCall(apiName, dataMap, cookie, extraQueryParams);
        if (body == null) {
            throw new IllegalStateException("平台搜索请求失败，请稍后重试");
        }
        if (body.contains("FAIL_SYS_TOKEN_EXPIRED") || body.contains("FAIL_SYS_TOKEN_EXOIRED")) {
            log.info("游客令牌过期，重新领取后重试: apiName={}", apiName);
            cookie = getGuestCookie(true);
            body = doCall(apiName, dataMap, cookie, extraQueryParams);
            if (body == null) {
                throw new IllegalStateException("平台搜索请求失败，请稍后重试");
            }
        }
        return body;
    }

    /**
     * 获取游客 Cookie（含 _m_h5_tk / _m_h5_tk_enc），过期或强制刷新时重新领取。
     */
    private synchronized String getGuestCookie(boolean forceRefresh) {
        if (!forceRefresh && cachedCookie != null && System.currentTimeMillis() < cachedExpireAt) {
            return cachedCookie;
        }
        // 签名中的时间戳必须与 t 参数使用同一取值，否则网关校验不通过
        long timestamp = System.currentTimeMillis();
        String timestampText = String.valueOf(timestamp);
        Map<String, String> params = new HashMap<>();
        params.put("jsv", "2.7.4");
        params.put("appKey", "34839810");
        params.put("t", timestampText);
        params.put("sign", XianyuSignUtils.generateSign(timestampText, "", "{}"));
        params.put("v", "1.0");
        params.put("type", "originaljson");
        params.put("dataType", "json");
        params.put("api", BOOTSTRAP_API);
        params.put("timeout", "20000");

        StringBuilder url = new StringBuilder(BASE_URL).append(BOOTSTRAP_API).append("/1.0/?");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (url.charAt(url.length() - 1) != '?') {
                url.append('&');
            }
            url.append(entry.getKey()).append('=').append(entry.getValue());
        }

        Map<String, String> headers = XianyuApiUtils.buildStandardHeaders("");
        Map<String, String> body = new HashMap<>();
        body.put("data", "{}");

        HttpClientUtils.HttpResponseResult result = HttpClientUtils.postWithHeaders(
                url.toString(), headers, body);
        if (result == null || result.getHeaders() == null) {
            throw new IllegalStateException("无法领取平台游客令牌，请稍后重试");
        }
        HttpHeaders responseHeaders = result.getHeaders();
        List<String> setCookies = responseHeaders.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null || setCookies.isEmpty()) {
            throw new IllegalStateException("平台未返回游客令牌，请稍后重试");
        }

        String token = null;
        String tokenEnc = null;
        for (String setCookie : setCookies) {
            for (String pair : setCookie.split(";")) {
                String trimmed = pair.trim();
                if (trimmed.startsWith("_m_h5_tk=")) {
                    token = trimmed.substring("_m_h5_tk=".length());
                } else if (trimmed.startsWith("_m_h5_tk_enc=")) {
                    tokenEnc = trimmed.substring("_m_h5_tk_enc=".length());
                }
            }
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("平台未返回游客令牌，请稍后重试");
        }

        StringBuilder cookieBuilder = new StringBuilder("_m_h5_tk=").append(token);
        if (tokenEnc != null && !tokenEnc.isBlank()) {
            cookieBuilder.append("; _m_h5_tk_enc=").append(tokenEnc);
        }
        cachedCookie = cookieBuilder.toString();
        // _m_h5_tk 形如 "{token}_{过期毫秒}"，默认按 24 小时兜底
        long expireAt = System.currentTimeMillis() + 24L * 60 * 60 * 1000;
        int split = token.lastIndexOf('_');
        if (split > 0) {
            try {
                expireAt = Long.parseLong(token.substring(split + 1));
            } catch (NumberFormatException ignored) {
                // 保留兜底过期时间
            }
        }
        // 提前 10 分钟视为过期，避免边界失败
        cachedExpireAt = expireAt - 10L * 60 * 1000;
        log.info("已领取平台游客令牌，有效期至: {}", cachedExpireAt);
        return cachedCookie;
    }

    private String doCall(String apiName, Map<String, Object> dataMap, String cookie,
                          Map<String, String> extraQueryParams) {
        XianyuApiUtils.ApiCallResultWithHeaders result = XianyuApiUtils.callApiWithHeaders(
                apiName, dataMap, cookie, null, null, null, extraQueryParams);
        return result.getBody();
    }
}
