package com.cloudforge.backend.secrets.service;

import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.repository.UserRepository;
import com.cloudforge.backend.repository.WorkspaceRepository;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import com.cloudforge.backend.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emits non-sensitive secret lifecycle and access audit events.
 * Includes bounded cooldown throttling on repeated denied access attempts
 * to prevent database/log amplification storms.
 */
@Service
public class SecretAuditService {

    public static final String ACTION_SECRET_CREATED = "SECRET_CREATED";
    public static final String ACTION_SECRET_ROTATED = "SECRET_ROTATED";
    public static final String ACTION_SECRET_REVOKED = "SECRET_REVOKED";
    public static final String ACTION_SECRET_DELETED = "SECRET_DELETED";
    public static final String ACTION_SECRET_BINDING_UPDATED = "SECRET_BINDING_UPDATED";
    public static final String ACTION_SECRET_ACCESS_GRANTED = "SECRET_ACCESS_GRANTED";
    public static final String ACTION_SECRET_ACCESS_DENIED = "SECRET_ACCESS_DENIED";
    public static final String ACTION_SECRET_KEY_REWRAPPED = "SECRET_KEY_REWRAPPED";
    public static final String ACTION_SECRET_KEY_ROTATION_RUN = "SECRET_KEY_ROTATION_RUN";

    private static final int MAX_COOLDOWN_ENTRIES = 2048;

    private final AuditService auditService;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final SecretManagementProperties properties;
    private final ConcurrentHashMap<String, Instant> deniedAuditLastRecorded = new ConcurrentHashMap<>();

    public SecretAuditService(
            AuditService auditService,
            WorkspaceRepository workspaceRepository,
            UserRepository userRepository,
            SecretManagementProperties properties
    ) {
        this.auditService = auditService;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.properties = properties;
    }

    @Transactional
    public void recordSecretEvent(
            WorkspaceEntity workspace,
            UserEntity actor,
            String action,
            String resourceType,
            String resourceId,
            String summary,
            Map<String, Object> metadata
    ) {
        auditService.record(workspace, actor, action, resourceType, resourceId, summary, sanitizeSafeMetadata(metadata));
    }

    /**
     * Records a {@code SECRET_ACCESS_DENIED} event in an independent transaction so the denial
     * persists even when the calling transaction rolls back on authorization failure.
     * Applies per-scope cooldown rate limiting to protect against log/audit amplification.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordDeniedAccess(
            UUID workspaceId,
            UserEntity actor,
            String resourceType,
            String resourceId,
            String actorKey,
            String reason,
            Map<String, Object> metadata
    ) {
        int cooldownSeconds = Math.max(0, properties.getDeniedAuditCooldownSeconds());
        String rateLimitKey = (workspaceId != null ? workspaceId : "global")
                + "|" + resourceType
                + "|" + (resourceId != null ? resourceId : "-")
                + "|" + (actorKey != null ? actorKey : "anonymous")
                + "|" + (reason != null ? reason : "denied");

        Instant now = Instant.now();
        if (cooldownSeconds > 0) {
            pruneIfOversized(now, Duration.ofSeconds(cooldownSeconds));
            Instant previous = deniedAuditLastRecorded.get(rateLimitKey);
            if (previous != null && previous.plusSeconds(cooldownSeconds).isAfter(now)) {
                return false;
            }
            deniedAuditLastRecorded.put(rateLimitKey, now);
        }

        WorkspaceEntity workspace = workspaceId != null
                ? workspaceRepository.findById(workspaceId).orElse(null)
                : null;
        UserEntity committedActor = actor != null && actor.getId() != null
                ? userRepository.findById(actor.getId()).orElse(null)
                : null;

        Map<String, Object> fullMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            fullMetadata.putAll(sanitizeSafeMetadata(metadata));
        }
        fullMetadata.put("outcome", "DENIED");
        if (actorKey != null && !actorKey.isBlank()) {
            fullMetadata.put("principal", actorKey);
        }
        if (reason != null && !reason.isBlank()) {
            fullMetadata.put("reason", reason);
        }

        auditService.record(
                workspace,
                committedActor,
                ACTION_SECRET_ACCESS_DENIED,
                resourceType,
                resourceId,
                "Denied access to secret resource (" + (reason != null ? reason : "unauthorized") + ")",
                fullMetadata
        );
        return true;
    }

    /**
     * Clears rate-limit cache (primarily for isolated test runs).
     */
    public void clearRateLimitState() {
        deniedAuditLastRecorded.clear();
    }

    private void pruneIfOversized(Instant now, Duration cooldown) {
        if (deniedAuditLastRecorded.size() >= MAX_COOLDOWN_ENTRIES) {
            deniedAuditLastRecorded.entrySet().removeIf(entry ->
                    entry.getValue().plus(cooldown).isBefore(now)
            );
            if (deniedAuditLastRecorded.size() >= MAX_COOLDOWN_ENTRIES) {
                deniedAuditLastRecorded.clear();
            }
        }
    }

    private Map<String, Object> sanitizeSafeMetadata(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        input.forEach((k, v) -> {
            if (k != null && v != null) {
                safe.put(k, v);
            }
        });
        return safe;
    }
}
