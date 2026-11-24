package com.cloudforge.backend.oci.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "oci_artifact_events")
public class OciArtifactEventEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_run_id", nullable = false)
    private PipelineRunEntity pipelineRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "build_record_id")
    private OciBuildRecordEntity buildRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artifact_id")
    private OciImageArtifactEntity artifact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "publish_attempt_id")
    private OciPublishAttemptEntity publishAttempt;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "actor_label", nullable = false, length = 128)
    private String actorLabel;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "TEXT")
    private String metadataJson = "{}";

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

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

    public OciBuildRecordEntity getBuildRecord() {
        return buildRecord;
    }

    public void setBuildRecord(OciBuildRecordEntity buildRecord) {
        this.buildRecord = buildRecord;
    }

    public OciImageArtifactEntity getArtifact() {
        return artifact;
    }

    public void setArtifact(OciImageArtifactEntity artifact) {
        this.artifact = artifact;
    }

    public OciPublishAttemptEntity getPublishAttempt() {
        return publishAttempt;
    }

    public void setPublishAttempt(OciPublishAttemptEntity publishAttempt) {
        this.publishAttempt = publishAttempt;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public void setActorLabel(String actorLabel) {
        this.actorLabel = actorLabel;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message != null && message.length() > 500 ? message.substring(0, 500) : message;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson != null ? metadataJson : "{}";
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
