package com.cloudforge.backend.service;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.dto.WebhookAckResponse;
import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.WebhookDeliveryStatus;
import com.cloudforge.backend.exception.ApiException;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.github.GitHubAppTokenService;
import com.cloudforge.backend.github.WebhookSignatureVerifier;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.WebhookDeliveryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processes HMAC-SHA256 verified GitHub webhooks relevant to Phase 2 installation
 * and repository lifecycle events. Explicitly does NOT trigger builds or deployments.
 */
@Service
public class GitHubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);
    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "ping",
            "installation",
            "installation_repositories",
            "repository"
    );

    private final AppProperties appProperties;
    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookDeliveryRepository deliveryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final LinkedRepositoryRepository linkedRepositoryRepository;
    private final GitHubIntegrationService gitHubIntegrationService;
    private final GitHubAppTokenService tokenService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public GitHubWebhookService(
            AppProperties appProperties,
            WebhookSignatureVerifier signatureVerifier,
            WebhookDeliveryRepository deliveryRepository,
            GitHubInstallationRepository installationRepository,
            LinkedRepositoryRepository linkedRepositoryRepository,
            GitHubIntegrationService gitHubIntegrationService,
            GitHubAppTokenService tokenService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.appProperties = appProperties;
        this.signatureVerifier = signatureVerifier;
        this.deliveryRepository = deliveryRepository;
        this.installationRepository = installationRepository;
        this.linkedRepositoryRepository = linkedRepositoryRepository;
        this.gitHubIntegrationService = gitHubIntegrationService;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WebhookProcessingResult processWebhook(
            byte[] rawPayload,
            String signature256Header,
            String eventType,
            String deliveryId
    ) {
        if (!appProperties.getGithubApp().isWebhookSecretConfigured()) {
            log.warn("Rejected GitHub webhook because GITHUB_WEBHOOK_SECRET is not configured");
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub webhook verification is not configured on this server"
            );
        }

        if (!signatureVerifier.verify(rawPayload, signature256Header, appProperties.getGithubApp().getWebhookSecret())) {
            log.warn("Rejected GitHub webhook with missing or invalid signature deliveryId={}", sanitizeHeader(deliveryId));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-Hub-Signature-256 signature");
        }

        if (!StringUtils.hasText(eventType) || !StringUtils.hasText(deliveryId)) {
            throw new BadRequestException("Missing required X-GitHub-Event or X-GitHub-Delivery headers");
        }

        String normalizedEvent = eventType.trim().toLowerCase();
        String normalizedDeliveryId = deliveryId.trim();

        JsonNode payload = parsePayload(rawPayload);
        String action = payload.path("action").asText(null);
        Long githubInstallationId = payload.has("installation") && payload.path("installation").hasNonNull("id")
                ? payload.path("installation").path("id").asLong()
                : null;

        String payloadHash = signatureVerifier.sha256Hex(rawPayload);
        boolean supported = SUPPORTED_EVENTS.contains(normalizedEvent);
        Instant now = Instant.now();

        // Atomically claim the delivery_id BEFORE executing any side effects so concurrent
        // duplicate webhook deliveries cannot duplicate state mutations or abort the PostgreSQL transaction.
        int claimed = deliveryRepository.claimDelivery(
                UUID.randomUUID(),
                normalizedDeliveryId,
                normalizedEvent,
                action != null && action.length() > 64 ? action.substring(0, 64) : action,
                githubInstallationId,
                payloadHash,
                supported ? WebhookDeliveryStatus.PROCESSED.name() : WebhookDeliveryStatus.IGNORED.name(),
                supported
                        ? "Processing webhook delivery"
                        : "Event type not handled (builds/deployments disabled)",
                now,
                now,
                now
        );

        if (claimed == 0) {
            log.info("Ignored duplicate GitHub webhook deliveryId={} event={}", normalizedDeliveryId, normalizedEvent);
            return new WebhookProcessingResult(
                    HttpStatus.OK,
                    new WebhookAckResponse("duplicate", normalizedDeliveryId, normalizedEvent, "Webhook delivery already processed")
            );
        }

        if (!supported) {
            return new WebhookProcessingResult(
                    HttpStatus.ACCEPTED,
                    new WebhookAckResponse(
                            "ignored",
                            normalizedDeliveryId,
                            normalizedEvent,
                            "Event acknowledged and ignored; builds and deployments are not enabled"
                    )
            );
        }

        String summary = switch (normalizedEvent) {
            case "ping" -> "Webhook ping verified successfully";
            case "installation" -> handleInstallationEvent(action, payload, githubInstallationId);
            case "installation_repositories" -> handleInstallationRepositoriesEvent(action, payload, githubInstallationId);
            case "repository" -> handleRepositoryEvent(action, payload);
            default -> "Ignored event";
        };

        String truncatedSummary = summary != null && summary.length() > 500 ? summary.substring(0, 500) : summary;
        deliveryRepository.updateDeliverySummary(normalizedDeliveryId, truncatedSummary, Instant.now());

        log.info("Processed GitHub webhook deliveryId={} event={} action={}", normalizedDeliveryId, normalizedEvent, action);
        return new WebhookProcessingResult(
                HttpStatus.OK,
                new WebhookAckResponse("processed", normalizedDeliveryId, normalizedEvent, summary)
        );
    }

    private String handleInstallationEvent(String action, JsonNode payload, Long githubInstallationId) {
        if (githubInstallationId == null) {
            return "Installation event missing installation.id";
        }
        GitHubInstallationEntity installation = installationRepository
                .findByGithubInstallationId(githubInstallationId)
                .orElse(null);

        if (installation == null) {
            return "Received installation." + action + " for untracked installation #" + githubInstallationId;
        }

        String normalizedAction = action == null ? "" : action.toLowerCase();
        switch (normalizedAction) {
            case "deleted" -> {
                installation.setStatus(InstallationStatus.REVOKED);
                installationRepository.save(installation);
                gitHubIntegrationService.markInstallationRepositoriesStatus(
                        installation, RepositoryConnectionStatus.DISCONNECTED
                );
                tokenService.evictInstallationToken(githubInstallationId);
                auditService.record(
                        installation.getWorkspace(),
                        null,
                        "GITHUB_INSTALLATION_REVOKED",
                        "GITHUB_INSTALLATION",
                        installation.getId().toString(),
                        "GitHub App installation #" + githubInstallationId + " was deleted via webhook",
                        Map.of("githubInstallationId", githubInstallationId, "source", "webhook")
                );
                return "Marked installation #" + githubInstallationId + " REVOKED";
            }
            case "suspend" -> {
                installation.setStatus(InstallationStatus.SUSPENDED);
                installationRepository.save(installation);
                gitHubIntegrationService.markInstallationRepositoriesStatus(
                        installation, RepositoryConnectionStatus.SUSPENDED
                );
                tokenService.evictInstallationToken(githubInstallationId);
                auditService.record(
                        installation.getWorkspace(),
                        null,
                        "GITHUB_INSTALLATION_SUSPENDED",
                        "GITHUB_INSTALLATION",
                        installation.getId().toString(),
                        "GitHub App installation #" + githubInstallationId + " was suspended via webhook",
                        Map.of("githubInstallationId", githubInstallationId, "source", "webhook")
                );
                return "Marked installation #" + githubInstallationId + " SUSPENDED";
            }
            case "unsuspend" -> {
                installation.setStatus(InstallationStatus.ACTIVE);
                installationRepository.save(installation);
                gitHubIntegrationService.markInstallationRepositoriesStatus(
                        installation, RepositoryConnectionStatus.CONNECTED
                );
                auditService.record(
                        installation.getWorkspace(),
                        null,
                        "GITHUB_INSTALLATION_RESUMED",
                        "GITHUB_INSTALLATION",
                        installation.getId().toString(),
                        "GitHub App installation #" + githubInstallationId + " was unsuspended via webhook",
                        Map.of("githubInstallationId", githubInstallationId, "source", "webhook")
                );
                return "Marked installation #" + githubInstallationId + " ACTIVE";
            }
            default -> {
                auditService.record(
                        installation.getWorkspace(),
                        null,
                        "GITHUB_WEBHOOK_PROCESSED",
                        "WEBHOOK",
                        String.valueOf(githubInstallationId),
                        "Processed installation." + normalizedAction + " webhook event",
                        Map.of("event", "installation", "action", normalizedAction)
                );
                return "Recorded installation." + normalizedAction;
            }
        }
    }

    private String handleInstallationRepositoriesEvent(String action, JsonNode payload, Long githubInstallationId) {
        if (githubInstallationId == null) {
            return "installation_repositories event missing installation.id";
        }
        GitHubInstallationEntity installation = installationRepository
                .findByGithubInstallationId(githubInstallationId)
                .orElse(null);

        if (installation == null) {
            return "Received installation_repositories for untracked installation #" + githubInstallationId;
        }

        Instant now = Instant.now();
        int addedCount = 0;
        int removedCount = 0;

        JsonNode added = payload.path("repositories_added");
        if (added.isArray()) {
            for (JsonNode repoNode : added) {
                long repoId = repoNode.path("id").asLong(0L);
                if (repoId <= 0) {
                    continue;
                }
                LinkedRepositoryEntity repo = linkedRepositoryRepository
                        .findByWorkspaceIdAndGithubRepoId(installation.getWorkspace().getId(), repoId)
                        .orElseGet(LinkedRepositoryEntity::new);
                String fullName = repoNode.path("full_name").asText("");
                String name = repoNode.path("name").asText("");
                String owner = fullName.contains("/") ? fullName.substring(0, fullName.indexOf('/')) : installation.getAccountLogin();
                boolean isPrivate = repoNode.path("private").asBoolean(true);

                repo.setWorkspace(installation.getWorkspace());
                repo.setInstallation(installation);
                repo.setGithubRepoId(repoId);
                repo.setOwnerLogin(owner);
                repo.setName(name);
                repo.setFullName(StringUtils.hasText(fullName) ? fullName : owner + "/" + name);
                repo.setVisibility(isPrivate ? "private" : "public");
                if (!StringUtils.hasText(repo.getDefaultBranch())) {
                    repo.setDefaultBranch("main");
                }
                repo.setConnectionStatus(RepositoryConnectionStatus.CONNECTED);
                repo.setLastSyncedAt(now);
                linkedRepositoryRepository.save(repo);
                addedCount++;
            }
        }

        JsonNode removed = payload.path("repositories_removed");
        if (removed.isArray()) {
            for (JsonNode repoNode : removed) {
                long repoId = repoNode.path("id").asLong(0L);
                if (repoId <= 0) {
                    continue;
                }
                linkedRepositoryRepository.findByWorkspaceIdAndGithubRepoId(installation.getWorkspace().getId(), repoId)
                        .ifPresent(repo -> {
                            repo.setConnectionStatus(RepositoryConnectionStatus.DISCONNECTED);
                            repo.setLastSyncedAt(now);
                            linkedRepositoryRepository.save(repo);
                        });
                removedCount++;
            }
        }

        auditService.record(
                installation.getWorkspace(),
                null,
                "GITHUB_REPOSITORIES_UPDATED",
                "GITHUB_INSTALLATION",
                installation.getId().toString(),
                "Updated installation repositories via webhook (added=" + addedCount + ", removed=" + removedCount + ")",
                Map.of(
                        "githubInstallationId", githubInstallationId,
                        "action", action != null ? action : "",
                        "addedCount", addedCount,
                        "removedCount", removedCount
                )
        );
        return "Processed installation_repositories (added=" + addedCount + ", removed=" + removedCount + ")";
    }

    private String handleRepositoryEvent(String action, JsonNode payload) {
        JsonNode repoNode = payload.path("repository");
        long githubRepoId = repoNode.path("id").asLong(0L);
        if (githubRepoId <= 0) {
            return "Repository event missing repository.id";
        }

        List<LinkedRepositoryEntity> trackedRepos = linkedRepositoryRepository.findAllByGithubRepoId(githubRepoId);
        if (trackedRepos.isEmpty()) {
            return "Repository event for untracked repository #" + githubRepoId;
        }

        Instant now = Instant.now();
        String normalizedAction = action == null ? "" : action.toLowerCase();
        for (LinkedRepositoryEntity repo : trackedRepos) {
            if ("deleted".equals(normalizedAction)) {
                repo.setConnectionStatus(RepositoryConnectionStatus.DISCONNECTED);
            } else {
                if (repoNode.hasNonNull("name")) {
                    repo.setName(repoNode.path("name").asText());
                }
                if (repoNode.hasNonNull("full_name")) {
                    repo.setFullName(repoNode.path("full_name").asText());
                }
                if (repoNode.hasNonNull("default_branch")) {
                    repo.setDefaultBranch(repoNode.path("default_branch").asText());
                }
                if (repoNode.has("private")) {
                    boolean isPrivate = repoNode.path("private").asBoolean(true);
                    repo.setVisibility(repoNode.path("visibility").asText(isPrivate ? "private" : "public"));
                }
                if (repoNode.has("archived")) {
                    repo.setArchived(repoNode.path("archived").asBoolean(false));
                }
                if (repoNode.hasNonNull("description")) {
                    String desc = repoNode.path("description").asText();
                    repo.setDescription(desc.length() <= 500 ? desc : desc.substring(0, 500));
                }
            }
            repo.setLastSyncedAt(now);
            linkedRepositoryRepository.save(repo);
        }

        return "Updated " + trackedRepos.size() + " tracked repository record(s) for repository." + normalizedAction;
    }

    private JsonNode parsePayload(byte[] rawPayload) {
        try {
            return objectMapper.readTree(rawPayload);
        } catch (IOException ex) {
            throw new BadRequestException("Webhook payload is not valid JSON");
        }
    }

    private String sanitizeHeader(String value) {
        if (!StringUtils.hasText(value)) {
            return "missing";
        }
        String trimmed = value.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    public record WebhookProcessingResult(HttpStatus httpStatus, WebhookAckResponse response) {
    }
}
