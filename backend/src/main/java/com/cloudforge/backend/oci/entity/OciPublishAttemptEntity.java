package com.cloudforge.backend.oci.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "oci_publish_attempts")
public class OciPublishAttemptEntity extends BaseAuditableEntity {

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
    @JoinColumn(name = "attempt_id", nullable = false, unique = true)
    private StageExecutionAttemptEntity attempt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "build_record_id")
    private OciBuildRecordEntity buildRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registry_connection_id")
    private OciRegistryConnectionEntity registryConnection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artifact_id")
    private OciImageArtifactEntity artifact;

    @Column(name = "worker_id", nullable = false, length = 128)
    private String workerId;

    @Column(name = "registry_host", nullable = false, length = 255)
    private String registryHost;

    @Column(name = "image_repository", nullable = false, length = 255)
    private String imageRepository;

    @Column(name = "image_tag", length = 128)
    private String imageTag;

    @Column(name = "expected_digest", length = 128)
    private String expectedDigest;

    @Column(name = "reported_digest", length = 128)
    private String reportedDigest;

    @Column(name = "verified_digest", length = 128)
    private String verifiedDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OciPublishAttemptStatus status = OciPublishAttemptStatus.STARTED;

    @Column(name = "failure_category", length = 40)
    private String failureCategory;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

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

    public StageExecutionAttemptEntity getAttempt() {
        return attempt;
    }

    public void setAttempt(StageExecutionAttemptEntity attempt) {
        this.attempt = attempt;
    }

    public OciBuildRecordEntity getBuildRecord() {
        return buildRecord;
    }

    public void setBuildRecord(OciBuildRecordEntity buildRecord) {
        this.buildRecord = buildRecord;
    }

    public OciRegistryConnectionEntity getRegistryConnection() {
        return registryConnection;
    }

    public void setRegistryConnection(OciRegistryConnectionEntity registryConnection) {
        this.registryConnection = registryConnection;
    }

    public OciImageArtifactEntity getArtifact() {
        return artifact;
    }

    public void setArtifact(OciImageArtifactEntity artifact) {
        this.artifact = artifact;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getRegistryHost() {
        return registryHost;
    }

    public void setRegistryHost(String registryHost) {
        this.registryHost = registryHost;
    }

    public String getImageRepository() {
        return imageRepository;
    }

    public void setImageRepository(String imageRepository) {
        this.imageRepository = imageRepository;
    }

    public String getImageTag() {
        return imageTag;
    }

    public void setImageTag(String imageTag) {
        this.imageTag = imageTag;
    }

    public String getExpectedDigest() {
        return expectedDigest;
    }

    public void setExpectedDigest(String expectedDigest) {
        this.expectedDigest = expectedDigest;
    }

    public String getReportedDigest() {
        return reportedDigest;
    }

    public void setReportedDigest(String reportedDigest) {
        this.reportedDigest = reportedDigest;
    }

    public String getVerifiedDigest() {
        return verifiedDigest;
    }

    public void setVerifiedDigest(String verifiedDigest) {
        this.verifiedDigest = verifiedDigest;
    }

    public OciPublishAttemptStatus getStatus() {
        return status;
    }

    public void setStatus(OciPublishAttemptStatus status) {
        this.status = status;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary != null && errorSummary.length() > 500 ? errorSummary.substring(0, 500) : errorSummary;
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
}
