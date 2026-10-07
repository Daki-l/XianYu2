package com.xianyu2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.controller.dto.VersionInfoRespDTO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 版本检测与更新任务提交。实际下载、验签和重启只由宿主机更新代理执行。
 */
@Service
public class SystemUpdateService {
    private static final String RELEASE_API = "https://api.github.com/repos/Daki-l/XianYu2/releases/latest";
    private static final String ASSET_API_PREFIX = "https://api.github.com/repos/Daki-l/XianYu2/releases/assets/";
    private static final String MANIFEST_NAME = "release-manifest.json";
    private static final int UPDATE_PROTOCOL_SCHEMA_VERSION = 1;
    private static final Pattern RELEASE_TAG = Pattern.compile("^v(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)$");
    private static final Pattern TASK_ID = Pattern.compile("^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$");
    private static final Set<String> ACTIVE_UPDATE_STATUSES = Set.of(
            "REQUESTED", "CHECKING", "DOWNLOADING", "VERIFYING", "BACKING_UP",
            "INSTALLING", "RESTARTING", "HEALTH_CHECKING");
    private static final Set<String> TERMINAL_UPDATE_STATUSES = Set.of("SUCCESS", "FAILED", "MANUAL_REQUIRED");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String currentVersion;
    private final String releaseApi;
    private final Path requestDirectory;
    private final Path statusDirectory;
    private final boolean updateEnabled;

    public SystemUpdateService(ObjectMapper objectMapper,
                               ObjectProvider<BuildProperties> buildPropertiesProvider,
                               @Value("${app.version:}") String configuredVersion,
                               @Value("${app.update.release-api:" + RELEASE_API + "}") String releaseApi,
                                @Value("${app.update.enabled:false}") boolean updateEnabled,
                               @Value("${app.update.request-dir:/app/update/request}") String updateRequestDir,
                               @Value("${app.update.status-dir:/app/update/status}") String updateStatusDir) {
        this(objectMapper,
                resolveVersion(configuredVersion, buildPropertiesProvider.getIfAvailable()),
                releaseApi,
                Path.of(updateRequestDir),
                Path.of(updateStatusDir),
                 updateEnabled,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    SystemUpdateService(ObjectMapper objectMapper, String currentVersion, String releaseApi,
                        Path requestDirectory, Path statusDirectory, HttpClient httpClient) {
        this(objectMapper, currentVersion, releaseApi, requestDirectory, statusDirectory, true, httpClient);
    }

    SystemUpdateService(ObjectMapper objectMapper, String currentVersion, String releaseApi,
                        Path requestDirectory, Path statusDirectory, boolean updateEnabled, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.currentVersion = normalizeVersion(currentVersion);
        this.releaseApi = releaseApi;
        this.requestDirectory = requestDirectory;
        this.statusDirectory = statusDirectory;
        this.updateEnabled = updateEnabled;
        this.httpClient = httpClient;
    }

    public String currentVersion() {
        return currentVersion;
    }

    public boolean updateEnabled() {
        return updateEnabled;
    }

    public VersionInfoRespDTO checkUpdate() {
        VersionInfoRespDTO result = new VersionInfoRespDTO();
        result.setCurrentVersion(currentVersion);
        result.setLatestVersion(currentVersion);
        result.setHasUpdate(false);
        result.setUpdateEnabled(updateEnabled);
        if (releaseApi == null || releaseApi.isBlank()) {
            return result;
        }
        if (!RELEASE_API.equals(releaseApi)) {
            throw new IllegalStateException("更新服务地址不受信任");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASE_API))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "XianYu2")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("版本服务返回 HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("draft").asBoolean() || root.path("prerelease").asBoolean()) {
                throw new IllegalStateException("最新发行版不是正式版本");
            }
            String releaseTag = root.path("tag_name").asText("");
            if (!RELEASE_TAG.matcher(releaseTag).matches()) {
                throw new IllegalStateException("发行版 tag 不符合 vX.Y.Z 格式");
            }
            JsonNode manifest = findManifest(root.path("assets"));
            if (manifest == null) {
                throw new IllegalStateException("发行版缺少 " + MANIFEST_NAME);
            }

            String latestVersion = normalizeVersion(releaseTag);
            result.setLatestVersion(latestVersion);
            result.setReleaseTag(releaseTag);
            result.setHasUpdate(compareVersion(latestVersion, currentVersion) > 0);
            result.setUpdateContent(root.path("body").asText(""));
            result.setPublishedAt(root.path("published_at").asText(""));
            result.setDownloadUrl(root.path("html_url").asText(""));
            result.setManifestAssetId(manifest.path("id").asLong());
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("检查更新失败: " + e.getMessage(), e);
        }
    }

    public synchronized Map<String, Object> requestUpdate() {
        Map<String, Object> currentStatus = updateAgentStatus();
        if (!Boolean.TRUE.equals(currentStatus.get("statusTrusted"))) {
            throw new IllegalStateException("更新状态协议无法识别，请由宿主机管理员检查更新代理");
        }
        if (Boolean.TRUE.equals(currentStatus.get("active"))) {
            return currentStatus;
        }
        if (!updateEnabled) {
            throw new IllegalStateException("在线更新尚未由宿主机管理员启用");
        }

        VersionInfoRespDTO version = checkUpdate();
        if (!Boolean.TRUE.equals(version.getHasUpdate())) {
            throw new IllegalStateException("当前已经是最新版本");
        }
        try {
            Files.createDirectories(requestDirectory);
            if (!isAgentAvailable()) {
                throw new IllegalStateException("自动更新代理未就绪");
            }
            if (Files.exists(requestDirectory.resolve("request.json"))) {
                return updateAgentStatus();
            }

            String taskId = UUID.randomUUID().toString();
            String requestedAt = Instant.now().toString();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("schemaVersion", UPDATE_PROTOCOL_SCHEMA_VERSION);
            request.put("taskId", taskId);
            request.put("version", version.getLatestVersion());
            request.put("releaseTag", version.getReleaseTag());
            request.put("manifestAssetId", version.getManifestAssetId());
            request.put("requestedAt", requestedAt);

            // 代理是状态文件的唯一所有者。应用仅创建请求，由代理认领并写入状态。
            writeJsonAtomically(requestDirectory.resolve("request.json"), request);
            return updateAgentStatus();
        } catch (Exception e) {
            throw new IllegalStateException("提交自动更新失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> updateAgentStatus() {
        boolean available = isAgentAvailable();
        Path requestPath = requestDirectory.resolve("request.json");
        boolean requestPending = Files.exists(requestPath, LinkOption.NOFOLLOW_LINKS);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available);
        result.put("enabled", updateEnabled);
        result.put("requestPending", requestPending);
        try {
            Map<String, Object> status = readJson(statusDirectory.resolve("status.json"));
            Map<String, Object> request = readJson(requestPath);
            requireSupportedStatus(status);
            requireSupportedRequest(request);
            if (!request.isEmpty() && (status.isEmpty()
                    || !String.valueOf(request.get("taskId")).equals(String.valueOf(status.get("taskId")))
                    || !String.valueOf(request.get("version")).equals(String.valueOf(status.get("version")))
                    || !isActiveStatus(String.valueOf(status.get("status"))))) {
                status = new LinkedHashMap<>(request);
                status.put("status", "REQUESTED");
                status.put("progress", 0);
                status.put("message", "更新任务已提交，等待更新代理处理");
                status.put("updatedAt", request.get("requestedAt"));
            }
            result.putAll(status);
        } catch (Exception e) {
            result.put("status", "MANUAL_REQUIRED");
            result.put("progress", 0);
            result.put("message", "更新协议无法识别，请检查宿主机更新代理");
            result.put("statusTrusted", false);
        }

        String status = String.valueOf(result.getOrDefault("status", "IDLE"));
        result.put("status", status);
        result.putIfAbsent("progress", 0);
        result.putIfAbsent("message", available ? "暂无更新任务" : "自动更新代理未就绪");
        result.putIfAbsent("downloadedBytes", 0L);
        result.putIfAbsent("totalBytes", 0L);
        result.putIfAbsent("statusTrusted", true);
        result.put("active", Boolean.TRUE.equals(result.get("statusTrusted"))
                && (requestPending || isActiveStatus(status)));
        result.put("canRetry", "FAILED".equals(status) && !requestPending
                && Boolean.TRUE.equals(result.get("statusTrusted")));
        return result;
    }

    private void requireSupportedSchema(Map<String, Object> document, String documentType) {
        if (document.isEmpty()) {
            return;
        }
        Object schemaVersion = document.get("schemaVersion");
        if (!(schemaVersion instanceof Number number)
                || number.intValue() != UPDATE_PROTOCOL_SCHEMA_VERSION
                || number.doubleValue() != UPDATE_PROTOCOL_SCHEMA_VERSION) {
            throw new IllegalStateException(documentType + "文件协议版本不受支持");
        }
    }

    private void requireSupportedRequest(Map<String, Object> request) {
        requireSupportedSchema(request, "请求");
        if (request.isEmpty()) {
            return;
        }
        if (!isTaskId(request.get("taskId"))
                || !isReleaseVersion(request.get("version"))
                || !RELEASE_TAG.matcher(String.valueOf(request.get("releaseTag"))).matches()
                || !(request.get("requestedAt") instanceof String requestedAt) || requestedAt.isBlank()) {
            throw new IllegalStateException("请求文件内容不完整");
        }
    }

    private void requireSupportedStatus(Map<String, Object> status) {
        requireSupportedSchema(status, "状态");
        if (status.isEmpty()) {
            return;
        }
        String value = status.get("status") instanceof String statusValue ? statusValue : "";
        if ((!ACTIVE_UPDATE_STATUSES.contains(value) && !TERMINAL_UPDATE_STATUSES.contains(value))
                || !isTaskId(status.get("taskId"))
                || !isReleaseVersion(status.get("version"))) {
            throw new IllegalStateException("状态文件内容不完整或状态不受支持");
        }
    }

    private boolean isTaskId(Object value) {
        return value instanceof String taskId && TASK_ID.matcher(taskId).matches();
    }

    private boolean isReleaseVersion(Object value) {
        return value instanceof String version
                && version.matches("^(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)$");
    }

    private JsonNode findManifest(JsonNode assets) {
        if (!assets.isArray()) {
            return null;
        }
        for (JsonNode asset : assets) {
            if (MANIFEST_NAME.equals(asset.path("name").asText())
                    && asset.path("id").canConvertToLong()
                    && asset.path("url").asText("").startsWith(ASSET_API_PREFIX)) {
                return asset;
            }
        }
        return null;
    }

    private boolean isAgentAvailable() {
        return Files.isDirectory(requestDirectory)
                && Files.isWritable(requestDirectory)
                && Files.isDirectory(statusDirectory)
                && Files.isRegularFile(statusDirectory.resolve("agent.ready"), LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isActiveStatus(String status) {
        return ACTIVE_UPDATE_STATUSES.contains(status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Collections.emptyMap();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("更新协议文件不是普通文件");
        }
        return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), LinkedHashMap.class);
    }

    private void writeJsonAtomically(Path target, Map<String, Object> content) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, objectMapper.writeValueAsString(content), StandardCharsets.UTF_8);
        if (Files.getFileStore(temporary).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r-----"));
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static int compareVersion(String first, String second) {
        String[] firstParts = normalizeVersion(first).split("\\.");
        String[] secondParts = normalizeVersion(second).split("\\.");
        int length = Math.max(firstParts.length, secondParts.length);
        for (int i = 0; i < length; i++) {
            int firstNumber = i < firstParts.length ? parseNumber(firstParts[i]) : 0;
            int secondNumber = i < secondParts.length ? parseNumber(secondParts[i]) : 0;
            if (firstNumber != secondNumber) {
                return Integer.compare(firstNumber, secondNumber);
            }
        }
        return 0;
    }

    static String resolveVersion(String configuredVersion, BuildProperties buildProperties) {
        if (configuredVersion != null && !configuredVersion.isBlank()) {
            return configuredVersion;
        }
        if (buildProperties != null && buildProperties.getVersion() != null && !buildProperties.getVersion().isBlank()) {
            return buildProperties.getVersion();
        }
        return "0.0.0-dev";
    }

    private static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0.0.0-dev";
        }
        return version.trim().replaceFirst("^[vV]\\.?", "");
    }

    private static int parseNumber(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9].*$", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
