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
@Table(name = "oci_build_records")
public class OciBuildRecordEntity extends BaseAuditableEntity {

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
    @JoinColumn(name = "linked_repository_id")
    private LinkedRepositoryEntity linkedRepository;

    @Column(name = "github_repository_id", nullable = false)
    private long githubRepositoryId;

    @Column(name = "repository_full_name", nullable = false, length = 255)
    private String repositoryFullName;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_handoff_id")
    private OciSourceHandoffEntity sourceHandoff;

    @Column(name = "source_checksum_sha256", length = 64)
    private String sourceChecksumSha256;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registry_connection_id")
    private OciRegistryConnectionEntity registryConnection;

    @Column(name = "dockerfile_path", nullable = false, length = 255)
    private String dockerfilePath;

    @Column(name = "build_context_path", nullable = false, length = 255)
    private String buildContextPath;

    @Column(name = "target_image_name", nullable = false, length = 255)
    private String targetImageName;

    @Column(name = "target_tag", length = 128)
    private String targetTag;

    @Column(name = "target_platform", nullable = false, length = 64)
    private String targetPlatform;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_mode", nullable = false, length = 32)
    private OciBuildMode buildMode;

    @Column(name = "config_checksum_sha256", nullable = false, length = 64)
    private String configChecksumSha256;

    @Column(name = "config_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String configSnapshotJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OciBuildStatus status = OciBuildStatus.PENDING;

    @Column(name = "built_manifest_digest", length = 128)
    private String builtManifestDigest;

    @Column(name = "image_media_type", length = 128)
    private String imageMediaType;

    @Column(name = "image_size_bytes")
    private Long imageSizeBytes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "oci_handoff_id")
    private OciSourceHandoffEntity ociHandoff;

    @Column(name = "worker_id", nullable = false, length = 128)
    private String workerId;

    @Column(name = "failure_category", length = 40)
    private String failureCategory;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

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

    public OciSourceHandoffEntity getSourceHandoff() {
        return sourceHandoff;
    }

    public void setSourceHandoff(OciSourceHandoffEntity sourceHandoff) {
        this.sourceHandoff = sourceHandoff;
    }

    public String getSourceChecksumSha256() {
        return sourceChecksumSha256;
    }

    public void setSourceChecksumSha256(String sourceChecksumSha256) {
        this.sourceChecksumSha256 = sourceChecksumSha256;
    }

    public OciRegistryConnectionEntity getRegistryConnection() {
        return registryConnection;
    }

    public void setRegistryConnection(OciRegistryConnectionEntity registryConnection) {
        this.registryConnection = registryConnection;
    }

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = dockerfilePath;
    }

    public String getBuildContextPath() {
        return buildContextPath;
    }

    public void setBuildContextPath(String buildContextPath) {
        this.buildContextPath = buildContextPath;
    }

    public String getTargetImageName() {
        return targetImageName;
    }

    public void setTargetImageName(String targetImageName) {
        this.targetImageName = targetImageName;
    }

    public String getTargetTag() {
        return targetTag;
    }

    public void setTargetTag(String targetTag) {
        this.targetTag = targetTag;
    }

    public String getTargetPlatform() {
        return targetPlatform;
    }

    public void setTargetPlatform(String targetPlatform) {
        this.targetPlatform = targetPlatform;
    }

    public OciBuildMode getBuildMode() {
        return buildMode;
    }

    public void setBuildMode(OciBuildMode buildMode) {
        this.buildMode = buildMode;
    }

    public String getConfigChecksumSha256() {
        return configChecksumSha256;
    }

    public void setConfigChecksumSha256(String configChecksumSha256) {
        this.configChecksumSha256 = configChecksumSha256;
    }

    public String getConfigSnapshotJson() {
        return configSnapshotJson;
    }

    public void setConfigSnapshotJson(String configSnapshotJson) {
        this.configSnapshotJson = configSnapshotJson;
    }

    public OciBuildStatus getStatus() {
        return status;
    }

    public void setStatus(OciBuildStatus status) {
        this.status = status;
    }

    public String getBuiltManifestDigest() {
        return builtManifestDigest;
    }

    public void setBuiltManifestDigest(String builtManifestDigest) {
        this.builtManifestDigest = builtManifestDigest;
    }

    public String getImageMediaType() {
        return imageMediaType;
    }

    public void setImageMediaType(String imageMediaType) {
        this.imageMediaType = imageMediaType;
    }

    public Long getImageSizeBytes() {
        return imageSizeBytes;
    }

    public void setImageSizeBytes(Long imageSizeBytes) {
        this.imageSizeBytes = imageSizeBytes;
    }

    public OciSourceHandoffEntity getOciHandoff() {
        return ociHandoff;
    }

    public void setOciHandoff(OciSourceHandoffEntity ociHandoff) {
        this.ociHandoff = ociHandoff;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
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

    public Long getRowVersion() {
        return rowVersion;
    }
}
