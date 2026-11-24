package com.cloudforge.backend.k8s.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
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
 * Operator-approved, deterministic namespace binding mapping a workspace project and
 * environment to an isolated namespace inside an approved Kubernetes cluster.
 */
@Entity
@Table(name = "kubernetes_namespace_bindings")
public class KubernetesNamespaceBindingEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cluster_connection_id", nullable = false)
    private KubernetesClusterConnectionEntity clusterConnection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false)
    private ProjectEnvironmentEntity environment;

    @Column(name = "namespace_name", nullable = false, length = 63)
    private String namespaceName;

    @Column(name = "workload_service_account_name", nullable = false, length = 128)
    private String workloadServiceAccountName = "cloudforge-workload";

    @Column(name = "image_pull_secret_name", length = 128)
    private String imagePullSecretName;

    @Enumerated(EnumType.STRING)
    @Column(name = "pod_security_standard", nullable = false, length = 32)
    private KubernetesPodSecurityStandard podSecurityStandard = KubernetesPodSecurityStandard.RESTRICTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_policy_mode", nullable = false, length = 32)
    private KubernetesNetworkPolicyMode networkPolicyMode = KubernetesNetworkPolicyMode.DEFAULT_DENY;

    @Column(name = "resource_quota_configured", nullable = false)
    private boolean resourceQuotaConfigured = true;

    @Column(name = "limit_range_configured", nullable = false)
    private boolean limitRangeConfigured = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private KubernetesNamespaceBindingStatus status = KubernetesNamespaceBindingStatus.ACTIVE;

    @Column(name = "verified_at")
    private Instant verifiedAt;

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

    public KubernetesClusterConnectionEntity getClusterConnection() {
        return clusterConnection;
    }

    public void setClusterConnection(KubernetesClusterConnectionEntity clusterConnection) {
        this.clusterConnection = clusterConnection;
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

    public String getNamespaceName() {
        return namespaceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = namespaceName;
    }

    public String getWorkloadServiceAccountName() {
        return workloadServiceAccountName;
    }

    public void setWorkloadServiceAccountName(String workloadServiceAccountName) {
        this.workloadServiceAccountName = workloadServiceAccountName;
    }

    public String getImagePullSecretName() {
        return imagePullSecretName;
    }

    public void setImagePullSecretName(String imagePullSecretName) {
        this.imagePullSecretName = imagePullSecretName;
    }

    public KubernetesPodSecurityStandard getPodSecurityStandard() {
        return podSecurityStandard;
    }

    public void setPodSecurityStandard(KubernetesPodSecurityStandard podSecurityStandard) {
        this.podSecurityStandard = podSecurityStandard;
    }

    public KubernetesNetworkPolicyMode getNetworkPolicyMode() {
        return networkPolicyMode;
    }

    public void setNetworkPolicyMode(KubernetesNetworkPolicyMode networkPolicyMode) {
        this.networkPolicyMode = networkPolicyMode;
    }

    public boolean isResourceQuotaConfigured() {
        return resourceQuotaConfigured;
    }

    public void setResourceQuotaConfigured(boolean resourceQuotaConfigured) {
        this.resourceQuotaConfigured = resourceQuotaConfigured;
    }

    public boolean isLimitRangeConfigured() {
        return limitRangeConfigured;
    }

    public void setLimitRangeConfigured(boolean limitRangeConfigured) {
        this.limitRangeConfigured = limitRangeConfigured;
    }

    public KubernetesNamespaceBindingStatus getStatus() {
        return status;
    }

    public void setStatus(KubernetesNamespaceBindingStatus status) {
        this.status = status;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
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
