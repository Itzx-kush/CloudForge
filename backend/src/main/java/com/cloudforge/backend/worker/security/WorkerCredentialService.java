package com.cloudforge.backend.worker.security;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.worker.config.WorkerProperties;
import com.cloudforge.backend.worker.entity.WorkerCredentialEntity;
import com.cloudforge.backend.worker.entity.WorkerCredentialStatus;
import com.cloudforge.backend.worker.repository.WorkerCredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Validates worker-specific credentials using constant-time SHA-256 hash comparison.
 *
 * <p>Supports credentials provisioned securely via environment variables
 * ({@code CLOUDFORGE_WORKER_CREDENTIALS}) and/or hashed database records
 * ({@code worker_credentials}) with expiration, rotation, and immediate revocation.
 * Never persists or logs plaintext secrets and fails closed when no active credentials exist.</p>
 */
@Service
public class WorkerCredentialService {

    private static final Logger log = LoggerFactory.getLogger(WorkerCredentialService.class);

    private final WorkerProperties workerProperties;
    private final WorkerCredentialRepository credentialRepository;

    public WorkerCredentialService(
            WorkerProperties workerProperties,
            WorkerCredentialRepository credentialRepository
    ) {
        this.workerProperties = workerProperties;
        this.credentialRepository = credentialRepository;
    }

    public boolean isWorkerPlaneEnabled() {
        return workerProperties.isEnabled();
    }

    @Transactional(readOnly = true)
    public boolean hasAnyConfiguredCredentials() {
        if (!parseEnvCredentials().isEmpty()) {
            return true;
        }
        return credentialRepository.countByStatus(WorkerCredentialStatus.ACTIVE) > 0;
    }

    /**
     * Authenticates a worker using its {@code workerId} and raw bearer token.
     * Enforces credential binding to {@code workerId}, non-revocation, and expiration.
     */
    @Transactional(readOnly = true)
    public Optional<WorkerPrincipal> authenticate(String workerId, String rawToken) {
        if (!workerProperties.isEnabled()
                || !StringUtils.hasText(workerId)
                || !StringUtils.hasText(rawToken)) {
            return Optional.empty();
        }
        String normalizedWorkerId = workerId.trim();
        byte[] presentedHash = sha256Bytes(rawToken.trim());
        Instant now = Instant.now();

        // 1. Check database-backed hashed credentials (supports dynamic rotation & revocation)
        List<WorkerCredentialEntity> dbCredentials = credentialRepository
                .findAllByWorkerIdAndStatus(normalizedWorkerId, WorkerCredentialStatus.ACTIVE);
        for (WorkerCredentialEntity cred : dbCredentials) {
            if (cred.getExpiresAt() != null && !cred.getExpiresAt().isAfter(now)) {
                continue;
            }
            if (cred.getRevokedAt() != null) {
                continue;
            }
            byte[] storedHash = HexFormat.of().parseHex(cred.getTokenHash());
            if (MessageDigest.isEqual(presentedHash, storedHash)) {
                return Optional.of(new WorkerPrincipal(normalizedWorkerId, cred.getKeyId()));
            }
        }

        // 2. Check environment-provisioned worker credentials (also honoring DB revocations by keyId)
        for (EnvCredential envCred : parseEnvCredentials()) {
            if (!envCred.workerId().equals(normalizedWorkerId)) {
                continue;
            }
            if (envCred.expiresAt() != null && !envCred.expiresAt().isAfter(now)) {
                continue;
            }
            Optional<WorkerCredentialEntity> revocationRecord = credentialRepository.findByKeyId(envCred.keyId());
            if (revocationRecord.isPresent()
                    && (revocationRecord.get().getStatus() == WorkerCredentialStatus.REVOKED
                    || revocationRecord.get().getRevokedAt() != null)) {
                continue;
            }
            if (MessageDigest.isEqual(presentedHash, envCred.tokenHashBytes())) {
                return Optional.of(new WorkerPrincipal(normalizedWorkerId, envCred.keyId()));
            }
        }

        return Optional.empty();
    }

    /**
     * Stores a hashed credential record (never plaintext) for programmatic provisioning,
     * rotation, or automated security tests.
     */
    @Transactional
    public WorkerCredentialEntity provisionCredential(
            String workerId,
            String keyId,
            String rawSecret,
            Instant expiresAt,
            String description
    ) {
        WorkerCredentialEntity entity = new WorkerCredentialEntity();
        entity.setWorkerId(workerId.trim());
        entity.setKeyId(keyId.trim());
        entity.setTokenHash(sha256Hex(rawSecret.trim()));
        entity.setStatus(WorkerCredentialStatus.ACTIVE);
        entity.setExpiresAt(expiresAt);
        entity.setDescription(description);
        return credentialRepository.saveAndFlush(entity);
    }

    @Transactional
    public void revokeCredential(String keyId) {
        credentialRepository.findByKeyId(keyId).ifPresent(cred -> {
            cred.setStatus(WorkerCredentialStatus.REVOKED);
            cred.setRevokedAt(Instant.now());
            credentialRepository.saveAndFlush(cred);
        });
    }

    private List<EnvCredential> parseEnvCredentials() {
        String rawConfig = workerProperties.getCredentials();
        if (!StringUtils.hasText(rawConfig)) {
            return List.of();
        }
        List<EnvCredential> result = new ArrayList<>();
        for (String entry : rawConfig.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Format: workerId:keyId:secret OR workerId:keyId:secret:expiresAtIso
            String[] parts = trimmed.split(":", 4);
            if (parts.length < 3) {
                log.warn("Ignoring malformed CLOUDFORGE_WORKER_CREDENTIALS entry (expected workerId:keyId:secret[:expiresAt])");
                continue;
            }
            String workerId = parts[0].trim();
            String keyId = parts[1].trim();
            String secret = parts[2].trim();
            if (!StringUtils.hasText(workerId) || !StringUtils.hasText(keyId)
                    || !AppProperties.isNonPlaceholder(secret) || secret.length() < 16) {
                log.warn("Ignoring invalid or weak worker credential entry for keyId={}", keyId);
                continue;
            }
            Instant expiresAt = null;
            if (parts.length == 4 && StringUtils.hasText(parts[3])) {
                try {
                    expiresAt = Instant.parse(parts[3].trim());
                } catch (Exception ex) {
                    log.warn("Ignoring worker credential entry with unparseable expiry for keyId={}", keyId);
                    continue;
                }
            }
            result.add(new EnvCredential(workerId, keyId, sha256Bytes(secret), expiresAt));
        }
        return result;
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    private static byte[] sha256Bytes(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private record EnvCredential(
            String workerId,
            String keyId,
            byte[] tokenHashBytes,
            Instant expiresAt
    ) {
    }
}
