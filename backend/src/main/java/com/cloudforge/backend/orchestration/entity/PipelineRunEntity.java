package com.cloudforge.backend.orchestration.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.orchestration.engine.PipelineStateMachine;
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
 * Persistent execution intent and lifecycle state for a CloudForge pipeline run.
 * Status mutations are strictly gated through {@link #transitionStatus} backed by
 * {@link PipelineStateMachine} so arbitrary entity updates cannot bypass invariants.
 */
@Entity
@Table(name = "pipeline_runs")
public class PipelineRunEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_definition_id")
    private PipelineDefinitionEntity pipelineDefinition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_repository_id")
    private LinkedRepositoryEntity linkedRepository;

    @Column(name = "run_number", nullable = false)
    private int runNumber;

    @Column(name = "definition_name", nullable = false, length = 120)
    private String definitionName;

    @Column(name = "definition_version", nullable = false)
    private int definitionVersion;

    @Column(name = "definition_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String definitionSnapshotJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 32)
    private PipelineTriggerType triggerType = PipelineTriggerType.MANUAL;

    @Column(name = "target_branch", length = 150)
    private String targetBranch;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PipelineRunStatus status = PipelineRunStatus.QUEUED;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id")
    private UserEntity requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_user_id")
    private UserEntity cancelledBy;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

    /**
     * Enforces {@link PipelineStateMachine} transition rules and records lifecycle timestamps atomically.
     */
    public PipelineRunStatus transitionStatus(
            PipelineRunStatus target,
            Instant transitionedAt,
            String reason,
            UserEntity actor
    ) {
        PipelineRunStatus previous = this.status;
        PipelineStateMachine.validateTransition(previous, target);
        Instant effectiveTime = transitionedAt != null ? transitionedAt : Instant.now();

        this.status = target;
        this.statusReason = truncate(reason, 500);

        switch (target) {
            case RUNNING -> this.startedAt = effectiveTime;
            case SUCCEEDED, FAILED -> this.completedAt = effectiveTime;
            case CANCELLED -> {
                this.cancelledAt = effectiveTime;
                this.completedAt = effectiveTime;
                this.cancelledBy = actor;
            }
            case QUEUED -> {
                // Initial state; validated unreachable via PipelineStateMachine
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

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public PipelineDefinitionEntity getPipelineDefinition() {
        return pipelineDefinition;
    }

    public void setPipelineDefinition(PipelineDefinitionEntity pipelineDefinition) {
        this.pipelineDefinition = pipelineDefinition;
    }

    public LinkedRepositoryEntity getLinkedRepository() {
        return linkedRepository;
    }

    public void setLinkedRepository(LinkedRepositoryEntity linkedRepository) {
        this.linkedRepository = linkedRepository;
    }

    public int getRunNumber() {
        return runNumber;
    }

    public void setRunNumber(int runNumber) {
        this.runNumber = runNumber;
    }

    public String getDefinitionName() {
        return definitionName;
    }

    public void setDefinitionName(String definitionName) {
        this.definitionName = definitionName;
    }

    public int getDefinitionVersion() {
        return definitionVersion;
    }

    public void setDefinitionVersion(int definitionVersion) {
        this.definitionVersion = definitionVersion;
    }

    public String getDefinitionSnapshotJson() {
        return definitionSnapshotJson;
    }

    public void setDefinitionSnapshotJson(String definitionSnapshotJson) {
        this.definitionSnapshotJson = definitionSnapshotJson;
    }

    public PipelineTriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(PipelineTriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public void setTargetBranch(String targetBranch) {
        this.targetBranch = targetBranch;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public PipelineRunStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setInitialStatusReason(String statusReason) {
        this.statusReason = truncate(statusReason, 500);
    }

    public UserEntity getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(UserEntity requestedBy) {
        this.requestedBy = requestedBy;
    }

    public UserEntity getCancelledBy() {
        return cancelledBy;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(Instant queuedAt) {
        this.queuedAt = queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
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
