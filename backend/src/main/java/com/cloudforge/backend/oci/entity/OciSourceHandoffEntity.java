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
import java.util.UUID;

@Entity
@Table(name = "oci_source_handoffs")
public class OciSourceHandoffEntity extends BaseAuditableEntity {

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
    @JoinColumn(name = "source_stage_run_id", nullable = false)
    private PipelineStageRunEntity sourceStageRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_attempt_id", nullable = false)
    private StageExecutionAttemptEntity sourceAttempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "handoff_type", nullable = false, length = 24)
    private OciHandoffType handoffType;

    @Column(name = "github_repository_id", nullable = false)
    private long githubRepositoryId;

    @Column(name = "repository_full_name", nullable = false, length = 255)
    private String repositoryFullName;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "content_checksum_sha256", nullable = false, length = 64)
    private String contentChecksumSha256;

    @Column(name = "archive_size_bytes", nullable = false)
    private long archiveSizeBytes;

    @Column(name = "extracted_bytes", nullable = false)
    private long extractedBytes = 0L;

    @Column(name = "file_count", nullable = false)
    private int fileCount = 0;

    @Column(name = "storage_token", nullable = false, length = 128)
    private String storageToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OciHandoffStatus status = OciHandoffStatus.AVAILABLE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_by_attempt_id")
    private UUID consumedByAttemptId;

    @Column(name = "cleaned_at")
    private Instant cleanedAt;

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

    public PipelineStageRunEntity getSourceStageRun() {
        return sourceStageRun;
    }

    public void setSourceStageRun(PipelineStageRunEntity sourceStageRun) {
        this.sourceStageRun = sourceStageRun;
    }

    public StageExecutionAttemptEntity getSourceAttempt() {
        return sourceAttempt;
    }

    public void setSourceAttempt(StageExecutionAttemptEntity sourceAttempt) {
        this.sourceAttempt = sourceAttempt;
    }

    public OciHandoffType getHandoffType() {
        return handoffType;
    }

    public void setHandoffType(OciHandoffType handoffType) {
        this.handoffType = handoffType;
    }

    public long getGithubRepositoryId() {
        return githubRepositoryId;
    }

    public void setGithubRepositoryId(long githubRepositoryId) {
        this.githubRepositoryId = githubRepositoryId;
    }

    public String getRepositoryFullName() {
        return repositoryFullName;
    }

    public void setRepositoryFullName(String repositoryFullName) {
        this.repositoryFullName = repositoryFullName;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getContentChecksumSha256() {
        return contentChecksumSha256;
    }

    public void setContentChecksumSha256(String contentChecksumSha256) {
        this.contentChecksumSha256 = contentChecksumSha256;
    }

    public long getArchiveSizeBytes() {
        return archiveSizeBytes;
    }

    public void setArchiveSizeBytes(long archiveSizeBytes) {
        this.archiveSizeBytes = archiveSizeBytes;
    }

    public long getExtractedBytes() {
        return extractedBytes;
    }

    public void setExtractedBytes(long extractedBytes) {
        this.extractedBytes = extractedBytes;
    }

    public int getFileCount() {
        return fileCount;
    }

    public void setFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    public String getStorageToken() {
        return storageToken;
    }

    public void setStorageToken(String storageToken) {
        this.storageToken = storageToken;
    }

    public OciHandoffStatus getStatus() {
        return status;
    }

    public void setStatus(OciHandoffStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    public UUID getConsumedByAttemptId() {
        return consumedByAttemptId;
    }

    public void setConsumedByAttemptId(UUID consumedByAttemptId) {
        this.consumedByAttemptId = consumedByAttemptId;
    }

    public Instant getCleanedAt() {
        return cleanedAt;
    }

    public void setCleanedAt(Instant cleanedAt) {
        this.cleanedAt = cleanedAt;
    }
}
