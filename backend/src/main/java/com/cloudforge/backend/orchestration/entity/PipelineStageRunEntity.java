package com.cloudforge.backend.orchestration.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.orchestration.engine.StageStateMachine;
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
 * Persistent stage instance within a CloudForge pipeline run.
 * Status transitions are strictly enforced through {@link #transitionStatus}
 * backed by {@link StageStateMachine}.
 */
@Entity
@Table(name = "pipeline_stage_runs")
public class PipelineStageRunEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_run_id", nullable = false)
    private PipelineRunEntity pipelineRun;

    @Column(name = "stage_key", nullable = false, length = 64)
    private String stageKey;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_type", nullable = false, length = 32)
    private PipelineStageType stageType;

    @Column(name = "execution_order", nullable = false)
    private int executionOrder;

    @Column(name = "depends_on_json", nullable = false, columnDefinition = "TEXT")
    private String dependsOnJson = "[]";

    @Column(name = "metadata_json", nullable = false, columnDefinition = "TEXT")
    private String metadataJson = "{}";

    @Column(name = "allowed_operation", length = 64)
    private String allowedOperation;

    @Column(name = "secret_bindings_json", nullable = false, columnDefinition = "TEXT")
    private String secretBindingsJson = "[]";

    @Column(name = "has_secret_bindings", nullable = false)
    private boolean hasSecretBindings = false;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 600;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 0;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber = 0;

    @Column(name = "allow_failure", nullable = false)
    private boolean allowFailure = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PipelineStageStatus status = PipelineStageStatus.PENDING;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "assigned_worker_id", length = 128)
    private String assignedWorkerId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "active_lease_token", length = 64)
    private String activeLeaseToken;

    @Column(name = "fencing_generation", nullable = false)
    private long fencingGeneration = 0L;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

    public PipelineStageStatus transitionStatus(
            PipelineStageStatus target,
            Instant transitionedAt,
            String reason
    ) {
        PipelineStageStatus previous = this.status;
        StageStateMachine.validateTransition(previous, target);
        Instant effectiveTime = transitionedAt != null ? transitionedAt : Instant.now();

        this.status = target;
        this.statusReason = truncate(reason, 500);

        switch (target) {
            case READY -> this.readyAt = effectiveTime;
            case RUNNING -> {
                this.startedAt = effectiveTime;
                this.attemptNumber = this.attemptNumber + 1;
                this.nextRetryAt = null;
            }
            case SUCCEEDED, FAILED, SKIPPED, CANCELLED -> {
                this.completedAt = effectiveTime;
                this.activeLeaseToken = null;
                this.leaseExpiresAt = null;
            }
            case PENDING -> {
                // Initial state; validated unreachable via StageStateMachine
            }
        }
        return previous;
    }

    /**
     * Atomically binds a fenced lease when a trusted worker claims a {@code READY} stage.
     */
    public long assignLease(String workerId, String leaseToken, Instant leaseExpiresAt, Instant claimedAt, String reason) {
        this.fencingGeneration = this.fencingGeneration + 1;
        this.assignedWorkerId = workerId;
        this.activeLeaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt;
        transitionStatus(PipelineStageStatus.RUNNING, claimedAt, reason);
        return this.fencingGeneration;
    }

    public void renewLease(Instant newLeaseExpiresAt) {
        this.leaseExpiresAt = newLeaseExpiresAt;
    }

    /**
     * Re-queues a {@code RUNNING} stage back to {@code READY} with retry backoff after a retryable
     * attempt failure or expired lease without ever transitioning terminal {@code FAILED} back to {@code RUNNING}.
     */
    public void requeueForRetry(Instant now, Instant retryAfter, String reason) {
        this.fencingGeneration = this.fencingGeneration + 1;
        this.assignedWorkerId = null;
        this.activeLeaseToken = null;
        this.leaseExpiresAt = null;
        this.nextRetryAt = retryAfter;
        transitionStatus(PipelineStageStatus.READY, now, reason);
    }

    public void fenceExpiredLease() {
        this.fencingGeneration = this.fencingGeneration + 1;
        this.activeLeaseToken = null;
        this.leaseExpiresAt = null;
    }

    public void recordCancellationRequest(Instant requestedAt) {
        if (this.cancellationRequestedAt == null) {
            this.cancellationRequestedAt = requestedAt != null ? requestedAt : Instant.now();
        }
    }

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

    public String getStageKey() {
        return stageKey;
    }

    public void setStageKey(String stageKey) {
        this.stageKey = stageKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public PipelineStageType getStageType() {
        return stageType;
    }

    public void setStageType(PipelineStageType stageType) {
        this.stageType = stageType;
    }

    public int getExecutionOrder() {
        return executionOrder;
    }

    public void setExecutionOrder(int executionOrder) {
        this.executionOrder = executionOrder;
    }

    public String getDependsOnJson() {
        return dependsOnJson;
    }

    public void setDependsOnJson(String dependsOnJson) {
        this.dependsOnJson = dependsOnJson;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson != null ? metadataJson : "{}";
    }

    public String getAllowedOperation() {
        return allowedOperation;
    }

    public void setAllowedOperation(String allowedOperation) {
        this.allowedOperation = allowedOperation;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public boolean isAllowFailure() {
        return allowFailure;
    }

    public void setAllowFailure(boolean allowFailure) {
        this.allowFailure = allowFailure;
    }

    public PipelineStageStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setInitialStatusReason(String statusReason) {
        this.statusReason = truncate(statusReason, 500);
    }

    public String getAssignedWorkerId() {
        return assignedWorkerId;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getActiveLeaseToken() {
        return activeLeaseToken;
    }

    public long getFencingGeneration() {
        return fencingGeneration;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public Instant getReadyAt() {
        return readyAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public String getSecretBindingsJson() {
        return secretBindingsJson;
    }

    public void setSecretBindingsJson(String secretBindingsJson) {
        this.secretBindingsJson = secretBindingsJson;
    }

    public boolean isHasSecretBindings() {
        return hasSecretBindings;
    }

    public void setHasSecretBindings(boolean hasSecretBindings) {
        this.hasSecretBindings = hasSecretBindings;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
