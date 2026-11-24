package com.cloudforge.backend.secrets.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Operator-level progress ledger for resumable, idempotent DEK rewrapping across master key rotations.
 */
@Entity
@Table(name = "secret_key_rotation_runs")
public class SecretKeyRotationRunEntity extends BaseAuditableEntity {

    @Column(name = "target_kek_id", nullable = false, length = 64)
    private String targetKekId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private KeyRotationRunStatus status = KeyRotationRunStatus.RUNNING;

    @Column(name = "total_candidates", nullable = false)
    private int totalCandidates = 0;

    @Column(name = "processed_count", nullable = false)
    private int processedCount = 0;

    @Column(name = "rewrapped_count", nullable = false)
    private int rewrappedCount = 0;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount = 0;

    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    @Column(name = "last_processed_version_id")
    private UUID lastProcessedVersionId;

    @Column(name = "initiated_by", nullable = false, length = 128)
    private String initiatedBy;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

    public String getTargetKekId() {
        return targetKekId;
    }

    public void setTargetKekId(String targetKekId) {
        this.targetKekId = targetKekId;
    }

    public KeyRotationRunStatus getStatus() {
        return status;
    }

    public void setStatus(KeyRotationRunStatus status) {
        this.status = status;
    }

    public int getTotalCandidates() {
        return totalCandidates;
    }

    public void setTotalCandidates(int totalCandidates) {
        this.totalCandidates = totalCandidates;
    }

    public int getProcessedCount() {
        return processedCount;
    }

    public void setProcessedCount(int processedCount) {
        this.processedCount = processedCount;
    }

    public int getRewrappedCount() {
        return rewrappedCount;
    }

    public void setRewrappedCount(int rewrappedCount) {
        this.rewrappedCount = rewrappedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(int skippedCount) {
        this.skippedCount = skippedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public UUID getLastProcessedVersionId() {
        return lastProcessedVersionId;
    }

    public void setLastProcessedVersionId(UUID lastProcessedVersionId) {
        this.lastProcessedVersionId = lastProcessedVersionId;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public void setInitiatedBy(String initiatedBy) {
        this.initiatedBy = initiatedBy;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary != null && errorSummary.length() > 500
                ? errorSummary.substring(0, 500)
                : errorSummary;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Long getRowVersion() {
        return rowVersion;
    }
}
