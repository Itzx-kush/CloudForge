package com.cloudforge.backend.oci.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
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
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "oci_image_artifacts")
public class OciImageArtifactEntity extends BaseAuditableEntity {

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
    @JoinColumn(name = "attempt_id", nullable = false)
    private StageExecutionAttemptEntity attempt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "build_record_id")
    private OciBuildRecordEntity buildRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registry_connection_id")
    private OciRegistryConnectionEntity registryConnection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_repository_id")
    private LinkedRepositoryEntity linkedRepository;

    @Column(name = "github_repository_id", nullable = false)
    private long githubRepositoryId;

    @Column(name = "repository_full_name", nullable = false, length = 255)
    private String repositoryFullName;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "registry_host", nullable = false, length = 255)
    private String registryHost;

    @Column(name = "image_repository", nullable = false, length = 255)
    private String imageRepository;

    @Column(name = "image_tag", length = 128)
    private String imageTag;

    @Column(name = "manifest_digest", nullable = false, length = 128)
    private String manifestDigest;

    @Column(name = "immutable_reference", nullable = false, length = 512)
    private String immutableReference;

    @Column(name = "image_media_type", nullable = false, length = 128)
    private String imageMediaType;

    @Column(name = "platform", nullable = false, length = 64)
    private String platform;

    @Column(name = "image_size_bytes")
    private Long imageSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private OciArtifactVerificationStatus verificationStatus = OciArtifactVerificationStatus.PENDING;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "retention_policy", nullable = false, length = 32)
    private OciArtifactRetentionPolicy retentionPolicy = OciArtifactRetentionPolicy.RETAIN_METADATA;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

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

    public LinkedRepositoryEntity getLinkedRepository() {
        return linkedRepository;
    }

    public void setLinkedRepository(LinkedRepositoryEntity linkedRepository) {
        this.linkedRepository = linkedRepository;
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

    public String getManifestDigest() {
        return manifestDigest;
    }

    public void setManifestDigest(String manifestDigest) {
        this.manifestDigest = manifestDigest;
    }

    public String getImmutableReference() {
        return immutableReference;
    }

    public void setImmutableReference(String immutableReference) {
        this.immutableReference = immutableReference;
    }

    public String getImageMediaType() {
        return imageMediaType;
    }

    public void setImageMediaType(String imageMediaType) {
        this.imageMediaType = imageMediaType;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public Long getImageSizeBytes() {
        return imageSizeBytes;
    }

    public void setImageSizeBytes(Long imageSizeBytes) {
        this.imageSizeBytes = imageSizeBytes;
    }

    public OciArtifactVerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(OciArtifactVerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public OciArtifactRetentionPolicy getRetentionPolicy() {
        return retentionPolicy;
    }

    public void setRetentionPolicy(OciArtifactRetentionPolicy retentionPolicy) {
        this.retentionPolicy = retentionPolicy;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary != null && errorSummary.length() > 500 ? errorSummary.substring(0, 500) : errorSummary;
    }

    public Long getRowVersion() {
        return rowVersion;
    }
}
