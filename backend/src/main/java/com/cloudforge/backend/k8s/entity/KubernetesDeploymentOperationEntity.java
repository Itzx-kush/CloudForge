package com.cloudforge.backend.k8s.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
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
import java.util.UUID;

/**
 * Immutable release/deployment ledger entry recording every Kubernetes rolling update,
 * its verified immutable OCI digest, manifest checksum, observed rollout state, and outcome.
 * Preserves full revision evidence for future Phase 3.7 rollback workflows.
 */
@Entity
@Table(name = "kubernetes_deployment_operations")
public class KubernetesDeploymentOperationEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false)
    private ProjectEnvironmentEntity environment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cluster_connection_id", nullable = false)
    private KubernetesClusterConnectionEntity clusterConnection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "namespace_binding_id", nullable = false)
    private KubernetesNamespaceBindingEntity namespaceBinding;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deployment_config_id")
    private KubernetesDeploymentConfigEntity deploymentConfig;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "oci_artifact_id", nullable = false)
    private OciImageArtifactEntity ociArtifact;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_run_id", nullable = false)
    private PipelineRunEntity pipelineRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_run_id", nullable = false)
    private PipelineStageRunEntity stageRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id")
    private StageExecutionAttemptEntity attempt;

    @Column(name = "revision_number", updatable = false)
    private Long revisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_kind", nullable = false, length = 16, updatable = false)
    private KubernetesOperationKind operationKind = KubernetesOperationKind.DEPLOYMENT;

    @Column(name = "restored_release_id", updatable = false)
    private UUID restoredReleaseId;

    @Column(name = "expected_operation_id", updatable = false)
    private UUID expectedOperationId;

    @Column(name = "guard_observation_id", updatable = false)
    private UUID guardObservationId;

    @Column(name = "operation_reason", length = 128, updatable = false)
    private String operationReason;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint_sha256", length = 64)
    private String requestFingerprintSha256;

    @Column(name = "namespace_name", nullable = false, length = 63)
    private String namespaceName;

    @Column(name = "deployment_name", nullable = false, length = 63)
    private String deploymentName;

    @Column(name = "service_name", nullable = false, length = 63)
    private String serviceName;

    @Column(name = "immutable_image_reference", nullable = false, length = 512)
    private String immutableImageReference;

    @Column(name = "manifest_digest", nullable = false, length = 128)
    private String manifestDigest;

    @Column(name = "source_commit_sha", nullable = false, length = 64)
    private String sourceCommitSha;

    @Column(name = "manifest_checksum_sha256", nullable = false, length = 64)
    private String manifestChecksumSha256;

    @Column(name = "config_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String configSnapshotJson;

    @Column(name = "requested_replicas", nullable = false)
    private int requestedReplicas;

    @Column(name = "observed_replicas", nullable = false)
    private int observedReplicas = 0;

    @Column(name = "updated_replicas", nullable = false)
    private int updatedReplicas = 0;

    @Column(name = "ready_replicas", nullable = false)
    private int readyReplicas = 0;

    @Column(name = "available_replicas", nullable = false)
    private int availableReplicas = 0;

    @Column(name = "observed_generation")
    private Long observedGeneration;

    @Column(name = "k8s_deployment_uid", length = 128)
    private String k8sDeploymentUid;

    @Column(name = "k8s_service_uid", length = 128)
    private String k8sServiceUid;

    @Column(name = "rollout_conditions_json", nullable = false, columnDefinition = "TEXT")
    private String rolloutConditionsJson = "[]";

    @Column(name = "pod_diagnostics_json", nullable = false, columnDefinition = "TEXT")
    private String podDiagnosticsJson = "[]";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KubernetesDeploymentStatus status = KubernetesDeploymentStatus.PENDING;

    @Column(name = "apply_initiated", nullable = false)
    private boolean applyInitiated = false;

    @Column(name = "assigned_worker_id", length = 128)
    private String assignedWorkerId;

    @Column(name = "fencing_generation", nullable = false)
    private long fencingGeneration = 0L;

    @Column(name = "production_approval_token", length = 128)
    private String productionApprovalToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_approved_by_user_id")
    private UserEntity productionApprovedBy;

    @Column(name = "production_approved_at")
    private Instant productionApprovedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id")
    private UserEntity requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_user_id")
    private UserEntity cancelledBy;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

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

    /**
     * Enforces valid Kubernetes deployment operation status transitions.
     */
    public KubernetesDeploymentStatus transitionStatus(KubernetesDeploymentStatus target, Instant now) {
        if (!this.status.canTransitionTo(target)) {
            throw new ConflictException(
                    "Invalid Kubernetes deployment status transition from " + this.status + " to " + target
            );
        }
        KubernetesDeploymentStatus previous = this.status;
        this.status = target;
        Instant effectiveTime = now != null ? now : Instant.now();
        if (target == KubernetesDeploymentStatus.VALIDATING && this.startedAt == null) {
            this.startedAt = effectiveTime;
        }
        if (target == KubernetesDeploymentStatus.APPLYING) {
            this.applyInitiated = true;
        }
        if (target.isTerminal() && this.completedAt == null) {
            this.completedAt = effectiveTime;
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

    public ProjectEnvironmentEntity getEnvironment() {
        return environment;
    }

    public void setEnvironment(ProjectEnvironmentEntity environment) {
        this.environment = environment;
    }

    public KubernetesClusterConnectionEntity getClusterConnection() {
        return clusterConnection;
    }

    public void setClusterConnection(KubernetesClusterConnectionEntity clusterConnection) {
        this.clusterConnection = clusterConnection;
    }

    public KubernetesNamespaceBindingEntity getNamespaceBinding() {
        return namespaceBinding;
    }

    public void setNamespaceBinding(KubernetesNamespaceBindingEntity namespaceBinding) {
        this.namespaceBinding = namespaceBinding;
    }

    public KubernetesDeploymentConfigEntity getDeploymentConfig() {
        return deploymentConfig;
    }

    public void setDeploymentConfig(KubernetesDeploymentConfigEntity deploymentConfig) {
        this.deploymentConfig = deploymentConfig;
    }

    public OciImageArtifactEntity getOciArtifact() {
        return ociArtifact;
    }

    public void setOciArtifact(OciImageArtifactEntity ociArtifact) {
        this.ociArtifact = ociArtifact;
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestFingerprintSha256() {
        return requestFingerprintSha256;
    }

    public void setRequestFingerprintSha256(String requestFingerprintSha256) {
        this.requestFingerprintSha256 = requestFingerprintSha256;
    }

    public String getNamespaceName() {
        return namespaceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = namespaceName;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public void setDeploymentName(String deploymentName) {
        this.deploymentName = deploymentName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getImmutableImageReference() {
        return immutableImageReference;
    }

    public void setImmutableImageReference(String immutableImageReference) {
        this.immutableImageReference = immutableImageReference;
    }

    public String getManifestDigest() {
        return manifestDigest;
    }

    public void setManifestDigest(String manifestDigest) {
        this.manifestDigest = manifestDigest;
    }

    public String getSourceCommitSha() {
        return sourceCommitSha;
    }

    public void setSourceCommitSha(String sourceCommitSha) {
        this.sourceCommitSha = sourceCommitSha;
    }

    public String getManifestChecksumSha256() {
        return manifestChecksumSha256;
    }

    public void setManifestChecksumSha256(String manifestChecksumSha256) {
        this.manifestChecksumSha256 = manifestChecksumSha256;
    }

    public String getConfigSnapshotJson() {
        return configSnapshotJson;
    }

    public void setConfigSnapshotJson(String configSnapshotJson) {
        this.configSnapshotJson = configSnapshotJson;
    }

    public int getRequestedReplicas() {
        return requestedReplicas;
    }

    public void setRequestedReplicas(int requestedReplicas) {
        this.requestedReplicas = requestedReplicas;
    }

    public int getObservedReplicas() {
        return observedReplicas;
    }

    public void setObservedReplicas(int observedReplicas) {
        this.observedReplicas = Math.max(0, observedReplicas);
    }

    public int getUpdatedReplicas() {
        return updatedReplicas;
    }

    public void setUpdatedReplicas(int updatedReplicas) {
        this.updatedReplicas = Math.max(0, updatedReplicas);
    }

    public int getReadyReplicas() {
        return readyReplicas;
    }

    public void setReadyReplicas(int readyReplicas) {
        this.readyReplicas = Math.max(0, readyReplicas);
    }

    public int getAvailableReplicas() {
        return availableReplicas;
    }

    public void setAvailableReplicas(int availableReplicas) {
        this.availableReplicas = Math.max(0, availableReplicas);
    }

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public String getK8sDeploymentUid() {
        return k8sDeploymentUid;
    }

    public void setK8sDeploymentUid(String k8sDeploymentUid) {
        this.k8sDeploymentUid = k8sDeploymentUid;
    }

    public String getK8sServiceUid() {
        return k8sServiceUid;
    }

    public void setK8sServiceUid(String k8sServiceUid) {
        this.k8sServiceUid = k8sServiceUid;
    }

    public String getRolloutConditionsJson() {
        return rolloutConditionsJson;
    }

    public void setRolloutConditionsJson(String rolloutConditionsJson) {
        this.rolloutConditionsJson = rolloutConditionsJson != null ? rolloutConditionsJson : "[]";
    }

    public String getPodDiagnosticsJson() {
        return podDiagnosticsJson;
    }

    public void setPodDiagnosticsJson(String podDiagnosticsJson) {
        this.podDiagnosticsJson = podDiagnosticsJson != null ? podDiagnosticsJson : "[]";
    }

    public KubernetesDeploymentStatus getStatus() {
        return status;
    }

    public boolean isApplyInitiated() {
        return applyInitiated;
    }

    public void setApplyInitiated(boolean applyInitiated) {
        this.applyInitiated = applyInitiated;
    }

    public String getAssignedWorkerId() {
        return assignedWorkerId;
    }

    public void setAssignedWorkerId(String assignedWorkerId) {
        this.assignedWorkerId = assignedWorkerId;
    }

    public long getFencingGeneration() {
        return fencingGeneration;
    }

    public void setFencingGeneration(long fencingGeneration) {
        this.fencingGeneration = fencingGeneration;
    }

    public String getProductionApprovalToken() {
        return productionApprovalToken;
    }

    public void setProductionApprovalToken(String productionApprovalToken) {
        this.productionApprovalToken = productionApprovalToken;
    }

    public UserEntity getProductionApprovedBy() {
        return productionApprovedBy;
    }

    public void setProductionApprovedBy(UserEntity productionApprovedBy) {
        this.productionApprovedBy = productionApprovedBy;
    }

    public Instant getProductionApprovedAt() {
        return productionApprovedAt;
    }

    public void setProductionApprovedAt(Instant productionApprovedAt) {
        this.productionApprovedAt = productionApprovedAt;
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

    public void setCancelledBy(UserEntity cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public void setCancellationRequestedAt(Instant cancellationRequestedAt) {
        this.cancellationRequestedAt = cancellationRequestedAt;
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
        this.errorSummary = errorSummary != null && errorSummary.length() > 500
                ? errorSummary.substring(0, 500)
                : errorSummary;
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
    public Long getRevisionNumber() {
        return revisionNumber;
    }

    public void setRevisionNumber(Long revisionNumber) {
        this.revisionNumber = revisionNumber;
    }

    public KubernetesOperationKind getOperationKind() {
        return operationKind;
    }

    public void setOperationKind(KubernetesOperationKind operationKind) {
        this.operationKind = operationKind;
    }

    public UUID getRestoredReleaseId() {
        return restoredReleaseId;
    }

    public void setRestoredReleaseId(UUID restoredReleaseId) {
        this.restoredReleaseId = restoredReleaseId;
    }

    public UUID getExpectedOperationId() {
        return expectedOperationId;
    }

    public void setExpectedOperationId(UUID expectedOperationId) {
        this.expectedOperationId = expectedOperationId;
    }

    public String getOperationReason() {
        return operationReason;
    }

    public void setOperationReason(String operationReason) {
        this.operationReason = operationReason;
    }

    public UUID getGuardObservationId() {
        return guardObservationId;
    }

    public void setGuardObservationId(UUID guardObservationId) {
        this.guardObservationId = guardObservationId;
    }
}
