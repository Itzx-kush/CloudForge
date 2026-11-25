package com.cloudforge.backend.k8s.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretEntity;
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
 * Workspace-scoped approved Kubernetes cluster connection storing safe endpoint & TLS CA
 * metadata and strictly referencing a Phase 3.4 AES-256-GCM encrypted secret for the
 * least-privilege deployment ServiceAccount bearer token.
 */
@Entity
@Table(name = "kubernetes_cluster_connections")
public class KubernetesClusterConnectionEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "cluster_identifier", nullable = false, length = 128)
    private String clusterIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "cluster_type", nullable = false, length = 32)
    private KubernetesClusterType clusterType = KubernetesClusterType.KIND_LOCAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment_category", nullable = false, length = 32)
    private EnvironmentType environmentCategory = EnvironmentType.DEVELOPMENT;

    @Column(name = "api_server_url", nullable = false, length = 512)
    private String apiServerUrl;

    @Column(name = "ca_certificate_pem", nullable = false, columnDefinition = "TEXT")
    private String caCertificatePem;

    @Column(name = "ca_certificate_fingerprint_sha256", nullable = false, length = 64)
    private String caCertificateFingerprintSha256;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_project_id", nullable = false)
    private ProjectEntity credentialProject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_environment_id", nullable = false)
    private ProjectEnvironmentEntity credentialEnvironment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bearer_token_secret_id", nullable = false)
    private EnvironmentSecretEntity bearerTokenSecret;

    @Column(name = "service_account_name", nullable = false, length = 128)
    private String serviceAccountName;

    @Column(name = "allow_local_loopback", nullable = false)
    private boolean allowLocalLoopback = false;

    @Column(name = "production_approval_required", nullable = false)
    private boolean productionApprovalRequired = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false, length = 32)
    private KubernetesClusterStatus connectionStatus = KubernetesClusterStatus.PENDING_VALIDATION;

    @Column(name = "validation_nonce", nullable = false)
    private long validationNonce = 0L;

    @Column(name = "validation_requested_at")
    private Instant validationRequestedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "last_validation_worker_id", length = 128)
    private String lastValidationWorkerId;

    @Column(name = "last_validation_error", length = 500)
    private String lastValidationError;

    @Column(name = "kubernetes_version", length = 64)
    private String kubernetesVersion;

    @Column(name = "supported_capabilities_json", nullable = false, columnDefinition = "TEXT")
    private String supportedCapabilitiesJson = "{}";

    @Column(name = "description", length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserEntity createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private UserEntity updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public void setWorkspace(WorkspaceEntity workspace) {
        this.workspace = workspace;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getClusterIdentifier() {
        return clusterIdentifier;
    }

    public void setClusterIdentifier(String clusterIdentifier) {
        this.clusterIdentifier = clusterIdentifier;
    }

    public KubernetesClusterType getClusterType() {
        return clusterType;
    }

    public void setClusterType(KubernetesClusterType clusterType) {
        this.clusterType = clusterType;
    }

    public EnvironmentType getEnvironmentCategory() {
        return environmentCategory;
    }

    public void setEnvironmentCategory(EnvironmentType environmentCategory) {
        this.environmentCategory = environmentCategory;
    }

    public String getApiServerUrl() {
        return apiServerUrl;
    }

    public void setApiServerUrl(String apiServerUrl) {
        this.apiServerUrl = apiServerUrl;
    }

    public String getCaCertificatePem() {
        return caCertificatePem;
    }

    public void setCaCertificatePem(String caCertificatePem) {
        this.caCertificatePem = caCertificatePem;
    }

    public String getCaCertificateFingerprintSha256() {
        return caCertificateFingerprintSha256;
    }

    public void setCaCertificateFingerprintSha256(String caCertificateFingerprintSha256) {
        this.caCertificateFingerprintSha256 = caCertificateFingerprintSha256;
    }

    public ProjectEntity getCredentialProject() {
        return credentialProject;
    }

    public void setCredentialProject(ProjectEntity credentialProject) {
        this.credentialProject = credentialProject;
    }

    public ProjectEnvironmentEntity getCredentialEnvironment() {
        return credentialEnvironment;
    }

    public void setCredentialEnvironment(ProjectEnvironmentEntity credentialEnvironment) {
        this.credentialEnvironment = credentialEnvironment;
    }

    public EnvironmentSecretEntity getBearerTokenSecret() {
        return bearerTokenSecret;
    }

    public void setBearerTokenSecret(EnvironmentSecretEntity bearerTokenSecret) {
        this.bearerTokenSecret = bearerTokenSecret;
    }

    public String getServiceAccountName() {
        return serviceAccountName;
    }

    public void setServiceAccountName(String serviceAccountName) {
        this.serviceAccountName = serviceAccountName;
    }

    public boolean isAllowLocalLoopback() {
        return allowLocalLoopback;
    }

    public void setAllowLocalLoopback(boolean allowLocalLoopback) {
        this.allowLocalLoopback = allowLocalLoopback;
    }

    public boolean isProductionApprovalRequired() {
        return productionApprovalRequired;
    }

    public void setProductionApprovalRequired(boolean productionApprovalRequired) {
        this.productionApprovalRequired = productionApprovalRequired;
    }

    public KubernetesClusterStatus getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(KubernetesClusterStatus connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public long getValidationNonce() {
        return validationNonce;
    }

    public void setValidationNonce(long validationNonce) {
        this.validationNonce = validationNonce;
    }

    public Instant getValidationRequestedAt() {
        return validationRequestedAt;
    }

    public void setValidationRequestedAt(Instant validationRequestedAt) {
        this.validationRequestedAt = validationRequestedAt;
    }

    public Instant getLastValidatedAt() {
        return lastValidatedAt;
    }

    public void setLastValidatedAt(Instant lastValidatedAt) {
        this.lastValidatedAt = lastValidatedAt;
    }

    public String getLastValidationWorkerId() {
        return lastValidationWorkerId;
    }

    public void setLastValidationWorkerId(String lastValidationWorkerId) {
        this.lastValidationWorkerId = lastValidationWorkerId;
    }

    public String getLastValidationError() {
        return lastValidationError;
    }

    public void setLastValidationError(String lastValidationError) {
        this.lastValidationError = lastValidationError != null && lastValidationError.length() > 500
                ? lastValidationError.substring(0, 500)
                : lastValidationError;
    }

    public String getKubernetesVersion() {
        return kubernetesVersion;
    }

    public void setKubernetesVersion(String kubernetesVersion) {
        this.kubernetesVersion = kubernetesVersion;
    }

    public String getSupportedCapabilitiesJson() {
        return supportedCapabilitiesJson;
    }

    public void setSupportedCapabilitiesJson(String supportedCapabilitiesJson) {
        this.supportedCapabilitiesJson = supportedCapabilitiesJson != null ? supportedCapabilitiesJson : "{}";
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UserEntity getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserEntity createdBy) {
        this.createdBy = createdBy;
    }

    public UserEntity getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UserEntity updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getRowVersion() {
        return rowVersion;
    }
}
