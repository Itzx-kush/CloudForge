package com.cloudforge.worker.client;

import com.cloudforge.worker.config.WorkerAgentConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless Java 17 HTTP client communicating strictly with CloudForge's authenticated
 * {@code /api/internal/workers/**} control-plane endpoints using per-worker credentials.
 * Never logs worker bearer tokens.
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
            String safeBody = path.endsWith("/secrets") ? "[REDACTED]" : response.body();
            throw new IllegalStateException(
                    "Control-plane request POST " + path + " failed with HTTP "
                            + response.statusCode() + ": " + safeBody
            );
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Control-plane request POST " + path + " error: " + ex.getMessage(), ex);
        }
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
