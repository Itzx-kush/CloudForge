package com.cloudforge.worker.client;

import com.cloudforge.worker.config.WorkerAgentConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless Java 17 HTTP client communicating strictly with CloudForge's authenticated
 * {@code /api/internal/workers/**} control-plane endpoints using per-worker credentials.
 * Never logs worker bearer tokens or registry credentials.
 */
public class HttpControlPlaneClient implements ControlPlaneClient {

    private static final String WORKER_ID_HEADER = "X-CloudForge-Worker-Id";

    private final WorkerAgentConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpControlPlaneClient(WorkerAgentConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void register(RegistrationPayload payload) {
        postJson("/api/internal/workers/register", payload, Void.class);
    }

    @Override
    public HeartbeatResult heartbeat(String workerId, List<ActiveLease> activeLeases) {
        return postJson(
                "/api/internal/workers/" + workerId + "/heartbeat",
                Map.of("activeLeases", activeLeases),
                HeartbeatResult.class
        );
    }

    @Override
    public Optional<ClaimedStageLease> claimNextStage(String workerId) {
        try {
            HttpRequest request = authorizedPost("/api/internal/workers/" + workerId + "/claim", "");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) {
                return Optional.empty();
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.of(objectMapper.readValue(response.body(), ClaimedStageLease.class));
            }
            throw new IllegalStateException(
                    "Control-plane claim failed with HTTP " + response.statusCode() + ": " + response.body()
            );
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Control-plane claim request error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void acknowledgeRunning(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            Instant startedAt
    ) {
        postJson(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/running",
                Map.of(
                        "leaseToken", leaseToken,
                        "fencingGeneration", fencingGeneration,
                        "startedAt", startedAt
                ),
                Void.class
        );
    }

    @Override
    public SecretDeliveryResult fetchAttemptSecrets(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration
    ) {
        if (!config.secretDeliveryEnabled()) {
            throw new IllegalStateException("Worker secret delivery is disabled in WorkerAgentConfig");
        }
        return postJson(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/secrets",
                Map.of(
                        "leaseToken", leaseToken,
                        "fencingGeneration", fencingGeneration
                ),
                SecretDeliveryResult.class
        );
    }

    @Override
    public URI fetchSourceArchive(String workerId, UUID attemptId, String leaseToken, long fencingGeneration) {
        if (!config.sourceOnly()) {
            throw new IllegalStateException("GitHub source acquisition is disabled");
        }
        ArchiveGrant grant = postJson(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/source-archive",
                Map.of("leaseToken", leaseToken, "fencingGeneration", fencingGeneration),
                ArchiveGrant.class
        );
        return grant == null ? null : grant.downloadUrl();
    }

    @Override
    public HandoffUploadResult uploadSourceHandoff(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            Path zipFile,
            String contentSha256,
            int fileCount,
            long extractedBytes
    ) {
        String path = "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/source-handoff";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.controlPlaneBaseUrl() + path))
                    .timeout(Duration.ofSeconds(60))
                    .header(WORKER_ID_HEADER, config.workerId())
                    .header("Authorization", "Bearer " + config.workerToken())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/octet-stream")
                    .header("X-CloudForge-Lease-Token", leaseToken)
                    .header("X-CloudForge-Fencing-Generation", String.valueOf(fencingGeneration))
                    .header("X-CloudForge-Content-Sha256", contentSha256)
                    .header("X-CloudForge-File-Count", String.valueOf(fileCount))
                    .header("X-CloudForge-Extracted-Bytes", String.valueOf(extractedBytes))
                    .POST(HttpRequest.BodyPublishers.ofFile(zipFile))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return objectMapper.readValue(resp.body(), HandoffUploadResult.class);
            }
            throw new IllegalStateException("Source handoff upload failed with HTTP " + resp.statusCode());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Source handoff upload error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public OciExecutionDescriptor fetchOciDescriptor(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration
    ) {
        return postJson(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/oci-descriptor",
                Map.of("leaseToken", leaseToken, "fencingGeneration", fencingGeneration),
                OciExecutionDescriptor.class
        );
    }

    @Override
    public DownloadedHandoff downloadBuildContext(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            Path destinationFile
    ) {
        return downloadHandoffBinary(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/build-context",
                leaseToken,
                fencingGeneration,
                destinationFile
        );
    }

    @Override
    public HandoffUploadResult uploadOciLayout(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            Path ociTarFile,
            String contentSha256,
            long imageSizeBytes
    ) {
        String path = "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/oci-layout-upload";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.controlPlaneBaseUrl() + path))
                    .timeout(Duration.ofSeconds(120))
                    .header(WORKER_ID_HEADER, config.workerId())
                    .header("Authorization", "Bearer " + config.workerToken())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/octet-stream")
                    .header("X-CloudForge-Lease-Token", leaseToken)
                    .header("X-CloudForge-Fencing-Generation", String.valueOf(fencingGeneration))
                    .header("X-CloudForge-Content-Sha256", contentSha256)
                    .header("X-CloudForge-Image-Size-Bytes", String.valueOf(imageSizeBytes))
                    .POST(HttpRequest.BodyPublishers.ofFile(ociTarFile))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return objectMapper.readValue(resp.body(), HandoffUploadResult.class);
            }
            throw new IllegalStateException("OCI layout upload failed with HTTP " + resp.statusCode());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("OCI layout upload error: " + ex.getMessage(), ex);
        }
    }

    @Override
    public DownloadedHandoff downloadOciLayout(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            Path destinationFile
    ) {
        return downloadHandoffBinary(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/oci-layout-download",
                leaseToken,
                fencingGeneration,
                destinationFile
        );
    }

    @Override
    public void reportOciBuildResult(String workerId, UUID attemptId, OciBuildResultPayload payload) {
        postJson(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/build-result",
                payload,
                Void.class
        );
    }

    @Override
    public RegistryCredentialResult fetchRegistryCredentials(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration
    ) {
        return postJson(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/registry-credentials",
                Map.of("leaseToken", leaseToken, "fencingGeneration", fencingGeneration),
                RegistryCredentialResult.class
        );
    }

    @Override
    public void reportOciPublishResult(String workerId, UUID attemptId, OciPublishResultPayload payload) {
        postJson(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/publish-result",
                payload,
                Void.class
        );
    }

    private DownloadedHandoff downloadHandoffBinary(
            String path,
            String leaseToken,
            long fencingGeneration,
            Path destinationFile
    ) {
        try {
            String json = objectMapper.writeValueAsString(
                    Map.of("leaseToken", leaseToken, "fencingGeneration", fencingGeneration)
            );
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.controlPlaneBaseUrl() + path))
                    .timeout(Duration.ofSeconds(120))
                    .header(WORKER_ID_HEADER, config.workerId())
                    .header("Authorization", "Bearer " + config.workerToken())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(destinationFile));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("Handoff download POST " + path + " failed with HTTP " + resp.statusCode());
            }
            String handoffId = resp.headers().firstValue("X-CloudForge-Handoff-Id").orElse(null);
            String sha256 = resp.headers().firstValue("X-CloudForge-Content-Sha256").orElse(null);
            String commitSha = resp.headers().firstValue("X-CloudForge-Commit-Sha").orElse(null);
            return new DownloadedHandoff(
                    handoffId != null ? UUID.fromString(handoffId) : null,
                    sha256,
                    commitSha,
                    destinationFile
            );
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Handoff download error: " + ex.getMessage(), ex);
        }
    }

    private record ArchiveGrant(URI downloadUrl) {
        @Override
        public String toString() {
            return "ArchiveGrant[downloadUrl=REDACTED]";
        }
    }

    @Override
    public void appendLogs(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            List<LogEntry> entries
    ) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        postJson(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/logs",
                Map.of(
                        "leaseToken", leaseToken,
                        "fencingGeneration", fencingGeneration,
                        "entries", entries
                ),
                Void.class
        );
    }

    @Override
    public void completeAttempt(String workerId, UUID attemptId, CompletionPayload payload) {
        postJson(
                "/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/complete",
                payload,
                Void.class
        );
    }

    @Override
    public void updateStatus(String workerId, String status) {
        postJson(
                "/api/internal/workers/" + workerId + "/status",
                Map.of("status", status),
                Void.class
        );
    }

    private <T> T postJson(String path, Object body, Class<T> responseType) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = authorizedPost(path, json);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (responseType == Void.class || response.body() == null || response.body().isBlank()) {
                    return null;
                }
                return objectMapper.readValue(response.body(), responseType);
            }
            String safeBody = isSensitiveResponse(path) ? "[REDACTED]" : response.body();
            throw new IllegalStateException(
                    "Control-plane request POST " + path + " failed with HTTP "
                            + response.statusCode() + ": " + safeBody
            );
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            if (isSensitiveResponse(path)) {
                throw new IllegalStateException("Sensitive control-plane request failed");
            }
            throw new IllegalStateException("Control-plane request POST " + path + " error: " + ex.getMessage(), ex);
        }
    }

    private static boolean isSensitiveResponse(String path) {
        return path.endsWith("/secrets")
                || path.endsWith("/source-archive")
                || path.endsWith("/registry-credentials");
    }

    private HttpRequest authorizedPost(String path, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.controlPlaneBaseUrl() + path))
                .timeout(Duration.ofSeconds(15))
                .header(WORKER_ID_HEADER, config.workerId())
                .header("Authorization", "Bearer " + config.workerToken())
                .header("Accept", "application/json");
        if (jsonBody != null && !jsonBody.isEmpty()) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }
}
