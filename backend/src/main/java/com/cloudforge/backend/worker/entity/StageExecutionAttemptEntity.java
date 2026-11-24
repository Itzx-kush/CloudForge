package com.cloudforge.backend.worker.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Persistent fenced execution attempt &amp; lease record for a single stage execution try.
 * Enables immutable attempt history and retry tracking without transitioning a terminal
 * stage FAILED state back to RUNNING.
 */
@Entity
@Table(name = "stage_execution_attempts")
public class StageExecutionAttemptEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_run_id", nullable = false)
    private PipelineRunEntity pipelineRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_run_id", nullable = false)
    private PipelineStageRunEntity stageRun;

    @Column(name = "worker_id", nullable = false, length = 128)
    private String workerId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "fencing_generation", nullable = false)
    private long fencingGeneration;

    @Column(name = "lease_token", nullable = false, unique = true, length = 64)
    private String leaseToken;

    @Column(name = "allowed_operation", nullable = false, length = 64)
    private String allowedOperation;

    @Column(name = "idempotent_operation", nullable = false)
    private boolean idempotentOperation = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AttemptStatus status = AttemptStatus.CLAIMED;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_category", nullable = false, length = 40)
    private AttemptFailureCategory failureCategory = AttemptFailureCategory.NONE;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "resource_usage_json", nullable = false, columnDefinition = "TEXT")
    private String resourceUsageJson = "{}";

    @Column(name = "lease_started_at", nullable = false)
    private Instant leaseStartedAt;

    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "termination_confirmed", nullable = false)
    private boolean terminationConfirmed = false;

    @Column(name = "recovered_at")
    private Instant recoveredAt;

    @Column(name = "secret_delivery_count", nullable = false)
    private int secretDeliveryCount = 0;

    @Column(name = "last_secret_delivery_at")
    private Instant lastSecretDeliveryAt;

    @Column(name = "source_archive_issued_at")
    private Instant sourceArchiveIssuedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public void setWorkspace(WorkspaceEntity workspace) {
        this.workspace = workspace;
    }

    public PipelineRunEntity getPipelineRun() {
        return pipelineRun;
    }

    public void setPipelineRun(PipelineRunEntity pipelineRun) {
        this.pipelineRun = pipelineRun;
    }

    public PipelineStageRunEntity getStageRun() {
        return stageRun;
    }

    public void setStageRun(PipelineStageRunEntity stageRun) {
        this.stageRun = stageRun;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public long getFencingGeneration() {
        return fencingGeneration;
    }

    public void setFencingGeneration(long fencingGeneration) {
        this.fencingGeneration = fencingGeneration;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(String leaseToken) {
        this.leaseToken = leaseToken;
    }

    public String getAllowedOperation() {
        return allowedOperation;
    }

    public void setAllowedOperation(String allowedOperation) {
        this.allowedOperation = allowedOperation;
    }

    public boolean isIdempotentOperation() {
        return idempotentOperation;
    }

    public void setIdempotentOperation(boolean idempotentOperation) {
        this.idempotentOperation = idempotentOperation;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public void setStatus(AttemptStatus status) {
        this.status = status;
    }

    public AttemptFailureCategory getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(AttemptFailureCategory failureCategory) {
        this.failureCategory = failureCategory;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary != null && summary.length() > 500 ? summary.substring(0, 500) : summary;
    }

    public String getResourceUsageJson() {
        return resourceUsageJson;
    }

    public void setResourceUsageJson(String resourceUsageJson) {
        this.resourceUsageJson = resourceUsageJson;
    }

    public Instant getLeaseStartedAt() {
        return leaseStartedAt;
    }

    public void setLeaseStartedAt(Instant leaseStartedAt) {
        this.leaseStartedAt = leaseStartedAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
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

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public void setCancellationRequestedAt(Instant cancellationRequestedAt) {
        this.cancellationRequestedAt = cancellationRequestedAt;
    }

    public boolean isTerminationConfirmed() {
        return terminationConfirmed;
    }

    public void setTerminationConfirmed(boolean terminationConfirmed) {
        this.terminationConfirmed = terminationConfirmed;
    }

    public Instant getRecoveredAt() {
        return recoveredAt;
    }

    public void setRecoveredAt(Instant recoveredAt) {
        this.recoveredAt = recoveredAt;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public Instant getSourceArchiveIssuedAt() {
        return sourceArchiveIssuedAt;
    }

    public void setSourceArchiveIssuedAt(Instant sourceArchiveIssuedAt) {
        this.sourceArchiveIssuedAt = sourceArchiveIssuedAt;
    }

    public int getSecretDeliveryCount() {
        return secretDeliveryCount;
    }

    public void setSecretDeliveryCount(int secretDeliveryCount) {
        this.secretDeliveryCount = secretDeliveryCount;
    }

    public Instant getLastSecretDeliveryAt() {
        return lastSecretDeliveryAt;
    }

    public void setLastSecretDeliveryAt(Instant lastSecretDeliveryAt) {
        this.lastSecretDeliveryAt = lastSecretDeliveryAt;
    }
}
