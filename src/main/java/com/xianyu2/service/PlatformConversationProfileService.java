package com.xianyu2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.entity.XianyuBuyerProfile;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.mapper.XianyuBuyerProfileMapper;
import com.xianyu2.utils.XianyuApiCallUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * 会话买家资料查询
 */
@Service
@RequiredArgsConstructor
public class PlatformConversationProfileService {

    private static final String PLATFORM_PROFILE_FETCH_ENABLED_SETTING = "platform_conversation_profile_fetch_enabled";
    private static final long CACHE_SECONDS = 1800;
    private static final long FAILED_CACHE_SECONDS = 300;

    private final XianyuAccountMapper accountMapper;
    private final XianyuBuyerProfileMapper buyerProfileMapper;
    private final AccountService accountService;
    private final XianyuApiCallUtils apiCallUtils;
    private final ObjectMapper objectMapper;
    private final SysSettingService sysSettingService;
    private final Map<String, CachedProfile> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Map<String, Object>>> inFlight = new ConcurrentHashMap<>();

    public List<Map<String, Object>> query(Long accountId, List<String> sessionIds) {
        XianyuAccount account = accountId == null ? null : accountMapper.selectById(accountId);
        if (account == null) {
            throw new IllegalArgumentException("账号不存在或无权访问");
        }
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = sessionIds.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (ids.size() != 1) {
            throw new IllegalArgumentException("一次只能查询一个会话资料");
        }
        return List.of(queryOne(account, ids.getFirst()));
    }

    private Map<String, Object> queryOne(XianyuAccount account, String sessionId) {
        Long accountId = account.getId();
        String cacheKey = accountId + ":" + sessionId;
        CachedProfile cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.profile();
        }
        boolean platformFetchEnabled = isPlatformFetchEnabled();
        Map<String, Object> persisted = persistedProfile(accountId, sessionId, platformFetchEnabled);
        if (persisted != null) {
            cacheProfile(cacheKey, persisted, CACHE_SECONDS);
            return persisted;
        }
        if (!platformFetchEnabled) {
            Map<String, Object> profile = emptyProfile(sessionId);
            cacheProfile(cacheKey, profile, FAILED_CACHE_SECONDS);
            return profile;
        }

        CompletableFuture<Map<String, Object>> created = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> existing = inFlight.putIfAbsent(cacheKey, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            String cookie = accountService.getCookieByAccountId(accountId);
            if (cookie == null || cookie.isBlank()) {
                Map<String, Object> failed = emptyProfile(sessionId);
                cacheProfile(cacheKey, failed, FAILED_CACHE_SECONDS);
                created.complete(failed);
                return failed;
            }
            Map<String, Object> profile = fetchProfile(account, cookie, sessionId);
            boolean loaded = !String.valueOf(profile.get("avatar")).isBlank()
                    || !String.valueOf(profile.get("nick")).isBlank();
            cacheProfile(cacheKey, profile, loaded ? CACHE_SECONDS : FAILED_CACHE_SECONDS);
            created.complete(profile);
            return profile;
        } catch (Exception e) {
            Map<String, Object> failed = emptyProfile(sessionId);
            cacheProfile(cacheKey, failed, FAILED_CACHE_SECONDS);
            created.complete(failed);
            return failed;
        } finally {
            inFlight.remove(cacheKey, created);
        }
    }

    private void cacheProfile(String cacheKey, Map<String, Object> profile, long seconds) {
        if (cache.size() >= 1000) {
            Instant now = Instant.now();
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        }
        cache.put(cacheKey, new CachedProfile(profile, Instant.now().plusSeconds(seconds)));
    }

    private Map<String, Object> fetchProfile(XianyuAccount account, String cookie, String sessionId) {
        Long accountId = account.getId();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", 0);
        request.put("sessionType", 1);
        request.put("sessionId", sessionId);
        request.put("isOwner", false);
        XianyuApiCallUtils.ApiCallResult response = apiCallUtils.callApiWithRetry(
                accountId, "mtop.taobao.idlemessage.pc.user.query", "4.0",
                request, cookie, null, Map.of(
                        "spm_cnt", "a21ybx.im.0.0",
                        "spm_pre", "a21ybx.home.sidebar.2.0",
                        "log_id", accountId + "-" + sessionId + "-" + System.currentTimeMillis()
                ));
        Map<String, Object> profile = emptyProfile(sessionId);
        if (response.isSuccess()) {
            Map<String, Object> user = extractUser(response.getResponse());
            profile.put("avatar", https(firstValue(user, "logo", "avatar")));
            profile.put("nick", firstValue(user, "nick", "nickname"));
            persistProfile(account, sessionId, profile, user);
        }
        return Map.copyOf(profile);
    }

    private Map<String, Object> persistedProfile(Long accountId, String sessionId, boolean requireFreshProfile) {
        XianyuBuyerProfile profile = buyerProfileMapper.findByBuyer(accountId, normalizeBuyerUserId(sessionId));
        if (profile == null || (requireFreshProfile && (profile.getProfileFetchedAt() == null
                || profile.getProfileFetchedAt().atZone(ZoneId.systemDefault()).toInstant()
                .isBefore(Instant.now().minusSeconds(CACHE_SECONDS))))) {
            return null;
        }
        Map<String, Object> result = emptyProfile(sessionId);
        result.put("avatar", safe(profile.getBuyerAvatarUrl()));
        result.put("nick", safe(profile.getBuyerUserName()));
        return Map.copyOf(result);
    }

    private boolean isPlatformFetchEnabled() {
        try {
            String value = sysSettingService.getSettingValue(PLATFORM_PROFILE_FETCH_ENABLED_SETTING);
            return "true".equalsIgnoreCase(value) || "1".equals(value);
        } catch (Exception ignored) {
            // 查询开关失败时保持关闭，避免展示增强功能影响账号平台请求配额。
            return false;
        }
    }

    private void persistProfile(XianyuAccount account, String sessionId, Map<String, Object> profile,
                                Map<String, Object> rawUser) {
        String nick = String.valueOf(profile.get("nick"));
        String avatar = String.valueOf(profile.get("avatar"));
        if (nick.isBlank() && avatar.isBlank()) {
            return;
        }
        try {
            buyerProfileMapper.touchPlatformProfile(account.getTenantId(), account.getId(),
                    normalizeBuyerUserId(sessionId), nick, avatar, objectMapper.writeValueAsString(rawUser));
        } catch (Exception e) {
            // 平台查询已成功，档案落库失败不应让页面资料变空。
        }
    }

    private Map<String, Object> emptyProfile(String sessionId) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("sid", sessionId);
        profile.put("avatar", "");
        profile.put("nick", "");
        return profile;
    }

    private String normalizeBuyerUserId(String sessionId) {
        return sessionId.replace("@goofish", "").trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> extractUser(String response) {
        try {
            Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() { });
            Map<String, Object> data = map(root.get("data"));
            Map<String, Object> module = map(data.get("module"));
            for (Object candidate : new Object[]{data.get("userInfo"), module.get("userInfo"), data, module}) {
                Map<String, Object> user = map(candidate);
                if (!firstValue(user, "logo", "avatar", "nick", "nickname").isBlank()) {
                    return user;
                }
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    private String firstValue(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private String https(String value) {
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        return value.startsWith("http://") ? "https://" + value.substring(7) : value;
    }

    private record CachedProfile(Map<String, Object> profile, Instant expiresAt) {
    }
}
