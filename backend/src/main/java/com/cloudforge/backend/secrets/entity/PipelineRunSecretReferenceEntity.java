package com.cloudforge.backend.secrets.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable pinned secret-version reference bound to a specific pipeline stage run
 * when the run is queued. Rotating a secret after queueing does not mutate this pin,
 * while revocation overrides delivery eligibility.
 */
@Entity
@Table(name = "pipeline_run_secret_references")
public class PipelineRunSecretReferenceEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_run_id", nullable = false)
    private PipelineRunEntity pipelineRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_run_id", nullable = false)
    private PipelineStageRunEntity stageRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false)
    private ProjectEnvironmentEntity environment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "secret_id", nullable = false)
    private EnvironmentSecretEntity secret;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "secret_version_id", nullable = false)
    private EnvironmentSecretVersionEntity secretVersion;

    @Column(name = "secret_name", nullable = false, length = 128)
    private String secretName;

    @Column(name = "delivery_alias", nullable = false, length = 128)
    private String deliveryAlias;

    @Column(name = "pinned_version_number", nullable = false)
    private int pinnedVersionNumber;

    @Column(name = "delivery_count", nullable = false)
    private int deliveryCount = 0;

    @Column(name = "last_delivered_at")
    private Instant lastDeliveredAt;

    @Column(name = "last_delivered_attempt_id")
    private UUID lastDeliveredAttemptId;

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

    public ProjectEnvironmentEntity getEnvironment() {
        return environment;
    }

    public void setEnvironment(ProjectEnvironmentEntity environment) {
        this.environment = environment;
    }

    public EnvironmentSecretEntity getSecret() {
        return secret;
    }

    public void setSecret(EnvironmentSecretEntity secret) {
        this.secret = secret;
    }

    public EnvironmentSecretVersionEntity getSecretVersion() {
        return secretVersion;
    }

    public void setSecretVersion(EnvironmentSecretVersionEntity secretVersion) {
        this.secretVersion = secretVersion;
    }

    public String getSecretName() {
        return secretName;
    }

    public void setSecretName(String secretName) {
        this.secretName = secretName;
    }

    public String getDeliveryAlias() {
        return deliveryAlias;
    }

    public void setDeliveryAlias(String deliveryAlias) {
        this.deliveryAlias = deliveryAlias;
    }

    public int getPinnedVersionNumber() {
        return pinnedVersionNumber;
    }

    public void setPinnedVersionNumber(int pinnedVersionNumber) {
        this.pinnedVersionNumber = pinnedVersionNumber;
    }

    public int getDeliveryCount() {
        return deliveryCount;
    }

    public void setDeliveryCount(int deliveryCount) {
        this.deliveryCount = deliveryCount;
    }

    public Instant getLastDeliveredAt() {
        return lastDeliveredAt;
    }

    public void setLastDeliveredAt(Instant lastDeliveredAt) {
        this.lastDeliveredAt = lastDeliveredAt;
    }

    public UUID getLastDeliveredAttemptId() {
        return lastDeliveredAttemptId;
    }

    public void setLastDeliveredAttemptId(UUID lastDeliveredAttemptId) {
        this.lastDeliveredAttemptId = lastDeliveredAttemptId;
    }
}
