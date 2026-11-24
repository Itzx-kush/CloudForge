package com.cloudforge.backend.worker.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent worker credential record storing ONLY the SHA-256 hex digest of the
 * worker-specific secret alongside lifecycle metadata (expiration, rotation keyId, revocation).
 */
@Entity
@Table(name = "worker_credentials")
public class WorkerCredentialEntity extends BaseAuditableEntity {

    @Column(name = "worker_id", nullable = false, length = 128)
    private String workerId;

    @Column(name = "key_id", nullable = false, unique = true, length = 64)
    private String keyId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private WorkerCredentialStatus status = WorkerCredentialStatus.ACTIVE;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "description", length = 255)
    private String description;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public WorkerCredentialStatus getStatus() {
        return status;
    }

    public void setStatus(WorkerCredentialStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
