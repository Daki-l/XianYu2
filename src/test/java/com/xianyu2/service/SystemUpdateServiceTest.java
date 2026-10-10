package com.xianyu2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.info.BuildProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemUpdateServiceTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void staleLookingActiveRequestIsNeverDeletedByApplication() throws Exception {
        Path requestDirectory = temporaryDirectory.resolve("update/request");
        Path statusDirectory = temporaryDirectory.resolve("update/status");
        Files.createDirectories(requestDirectory);
        Files.createDirectories(statusDirectory);
        Files.writeString(statusDirectory.resolve("agent.ready"), "ready");
        objectMapper.writeValue(requestDirectory.resolve("request.json").toFile(), Map.of(
                "schemaVersion", 1,
                "taskId", "123e4567-e89b-12d3-a456-426614174000",
                "version", "2.0.8",
                "releaseTag", "v2.0.8",
                "requestedAt", Instant.now().toString()));
        objectMapper.writeValue(statusDirectory.resolve("status.json").toFile(), Map.of(
                "schemaVersion", 1,
                "taskId", "123e4567-e89b-12d3-a456-426614174000",
                "version", "2.0.8",
                "status", "DOWNLOADING",
                "progress", 35,
                "updatedAt", Instant.now().minusSeconds(3600).toString()));

        SystemUpdateService service = service(requestDirectory, statusDirectory);
        Map<String, Object> status = service.updateAgentStatus();

        assertTrue(Files.exists(requestDirectory.resolve("request.json")));
        assertEquals("DOWNLOADING", status.get("status"));
        assertTrue((Boolean) status.get("active"));
    }

    @Test
    void claimedTaskRemainsActiveThroughAgentOwnedStatusDirectory() throws Exception {
        Path requestDirectory = temporaryDirectory.resolve("update/request");
        Path statusDirectory = temporaryDirectory.resolve("update/status");
        Files.createDirectories(requestDirectory);
        Files.createDirectories(statusDirectory);
        Files.writeString(statusDirectory.resolve("agent.ready"), "ready");
        objectMapper.writeValue(statusDirectory.resolve("status.json").toFile(), Map.of(
                "schemaVersion", 1,
                "taskId", "123e4567-e89b-12d3-a456-426614174000",
                "version", "2.0.8",
                "status", "HEALTH_CHECKING",
                "progress", 92));

        Map<String, Object> status = service(requestDirectory, statusDirectory).updateAgentStatus();

        assertFalse((Boolean) status.get("requestPending"));
        assertTrue((Boolean) status.get("active"));
        assertFalse((Boolean) status.get("canRetry"));
    }

    @Test
    void newRequestIsNotMaskedByThePreviousTerminalStatus() throws Exception {
        Path requestDirectory = temporaryDirectory.resolve("update/request");
        Path statusDirectory = temporaryDirectory.resolve("update/status");
        Files.createDirectories(requestDirectory);
        Files.createDirectories(statusDirectory);
        Files.writeString(statusDirectory.resolve("agent.ready"), "ready");
        objectMapper.writeValue(statusDirectory.resolve("status.json").toFile(), Map.of(
                "schemaVersion", 1,
                "taskId", "11111111-1111-1111-1111-111111111111",
                "version", "2.0.7",
                "status", "SUCCESS",
                "progress", 100));
        objectMapper.writeValue(requestDirectory.resolve("request.json").toFile(), Map.of(
                "schemaVersion", 1,
                "taskId", "22222222-2222-2222-2222-222222222222",
                "version", "2.0.8",
                "releaseTag", "v2.0.8",
                "requestedAt", Instant.now().toString()));

        Map<String, Object> status = service(requestDirectory, statusDirectory).updateAgentStatus();

        assertEquals("REQUESTED", status.get("status"));
        assertEquals("22222222-2222-2222-2222-222222222222", status.get("taskId"));
        assertTrue((Boolean) status.get("active"));
    }

    @Test
    void comparesReleaseVersionsNumerically() {
        assertTrue(SystemUpdateService.compareVersion("2.0.10", "2.0.9") > 0);
        assertTrue(SystemUpdateService.compareVersion("v2.0.8", "2.0.8") == 0);
        assertTrue(SystemUpdateService.compareVersion("2.0.8", "2.1.0") < 0);
    }

    @Test
    void usesBuildInfoWhenTheContainerDoesNotSetAnAppVersion() {
        Properties properties = new Properties();
        properties.setProperty("version", "2.0.8");

        assertEquals("2.0.8", SystemUpdateService.resolveVersion("", new BuildProperties(properties)));
        assertEquals("2.0.9", SystemUpdateService.resolveVersion("2.0.9", new BuildProperties(properties)));
    }

    @Test
    void updateStatusDoesNotExposeAnEnablementSwitch() throws Exception {
        Path requestDirectory = temporaryDirectory.resolve("update/request");
        Path statusDirectory = temporaryDirectory.resolve("update/status");
        Files.createDirectories(requestDirectory);
        Files.createDirectories(statusDirectory);
        Files.writeString(statusDirectory.resolve("agent.ready"), "ready");

        Map<String, Object> status = service(requestDirectory, statusDirectory).updateAgentStatus();

        assertTrue((Boolean) status.get("available"));
        assertFalse(status.containsKey("enabled"));
    }

    @Test
    void unsupportedProtocolStateBlocksNewRequests() throws Exception {
        Path requestDirectory = temporaryDirectory.resolve("update/request");
        Path statusDirectory = temporaryDirectory.resolve("update/status");
        Files.createDirectories(requestDirectory);
        Files.createDirectories(statusDirectory);
        Files.writeString(statusDirectory.resolve("agent.ready"), "ready");
        objectMapper.writeValue(statusDirectory.resolve("status.json").toFile(), Map.of(
                "schemaVersion", 2,
                "taskId", "123e4567-e89b-12d3-a456-426614174000",
                "version", "2.0.8",
                "status", "SUCCESS"));

        SystemUpdateService service = service(requestDirectory, statusDirectory);
        Map<String, Object> status = service.updateAgentStatus();

        assertEquals("MANUAL_REQUIRED", status.get("status"));
        assertFalse((Boolean) status.get("statusTrusted"));
        assertThrows(IllegalStateException.class, service::requestUpdate);
        assertFalse(Files.exists(requestDirectory.resolve("request.json")));
    }

    @Test
    void unknownStatusIsConservativelyReportedAsManualRequired() throws Exception {
        Path requestDirectory = temporaryDirectory.resolve("update/request");
        Path statusDirectory = temporaryDirectory.resolve("update/status");
        Files.createDirectories(requestDirectory);
        Files.createDirectories(statusDirectory);
        Files.writeString(statusDirectory.resolve("agent.ready"), "ready");
        objectMapper.writeValue(statusDirectory.resolve("status.json").toFile(), Map.of(
                "schemaVersion", 1,
                "taskId", "123e4567-e89b-12d3-a456-426614174000",
                "version", "2.0.8",
                "status", "UNRECOGNIZED"));

        Map<String, Object> status = service(requestDirectory, statusDirectory).updateAgentStatus();

        assertEquals("MANUAL_REQUIRED", status.get("status"));
        assertFalse((Boolean) status.get("statusTrusted"));
    }

    @Test
    void nonRegularRequestFileIsConservativelyReportedAsManualRequired() throws Exception {
        Path requestDirectory = temporaryDirectory.resolve("update/request");
        Path statusDirectory = temporaryDirectory.resolve("update/status");
        Files.createDirectories(requestDirectory.resolve("request.json"));
        Files.createDirectories(statusDirectory);
        Files.writeString(statusDirectory.resolve("agent.ready"), "ready");

        Map<String, Object> status = service(requestDirectory, statusDirectory).updateAgentStatus();

        assertEquals("MANUAL_REQUIRED", status.get("status"));
        assertFalse((Boolean) status.get("statusTrusted"));
    }

    @Test
    void requestIsWrittenWithAgentAcceptedPermissionsWhenPosixIsAvailable() throws Exception {
        Path requestDirectory = temporaryDirectory.resolve("update/request");
        Path statusDirectory = temporaryDirectory.resolve("update/status");
        Files.createDirectories(requestDirectory);
        Files.createDirectories(statusDirectory);
        Files.writeString(statusDirectory.resolve("agent.ready"), "ready");
        SystemUpdateService service = service(requestDirectory, statusDirectory);
        Path request = requestDirectory.resolve("request.json");

        var writer = SystemUpdateService.class.getDeclaredMethod("writeJsonAtomically", Path.class, Map.class);
        writer.setAccessible(true);
        writer.invoke(service, request, Map.of("schemaVersion", 1));

        if (Files.getFileStore(requestDirectory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(request));
        }
    }

    private SystemUpdateService service(Path requestDirectory, Path statusDirectory) {
        return new SystemUpdateService(objectMapper, "2.0.7",
                "https://api.github.com/repos/Daki-l/XianYu2/releases/latest",
                requestDirectory, statusDirectory, HttpClient.newHttpClient());
    }
}
