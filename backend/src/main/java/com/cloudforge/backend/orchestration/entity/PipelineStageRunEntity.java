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
            }
            case SUCCEEDED, FAILED, SKIPPED, CANCELLED -> this.completedAt = effectiveTime;
            case PENDING -> {
                // Initial state; validated unreachable via StageStateMachine
            }
        }
        return previous;
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

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
