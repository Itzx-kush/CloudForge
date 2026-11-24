package com.cloudforge.backend.k8s.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Validated declarative Deployment + ClusterIP Service configuration bound to a workspace,
 * project, environment, approved cluster, and approved namespace binding.
 */
@Entity
@Table(name = "kubernetes_deployment_configs")
public class KubernetesDeploymentConfigEntity extends BaseAuditableEntity {

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

    @Column(name = "namespace_name", nullable = false, length = 63)
    private String namespaceName;

    @Column(name = "deployment_name", nullable = false, length = 63)
    private String deploymentName;

    @Column(name = "service_name", nullable = false, length = 63)
    private String serviceName;

    @Column(name = "replica_count", nullable = false)
    private int replicaCount = 1;

    @Column(name = "container_port", nullable = false)
    private int containerPort = 8080;

    @Column(name = "service_port", nullable = false)
    private int servicePort = 80;

    @Column(name = "cpu_request", nullable = false, length = 32)
    private String cpuRequest = "100m";

    @Column(name = "cpu_limit", nullable = false, length = 32)
    private String cpuLimit = "500m";

    @Column(name = "memory_request", nullable = false, length = 32)
    private String memoryRequest = "128Mi";

    @Column(name = "memory_limit", nullable = false, length = 32)
    private String memoryLimit = "512Mi";

    @Column(name = "readiness_probe_json", nullable = false, columnDefinition = "TEXT")
    private String readinessProbeJson;

    @Column(name = "liveness_probe_json", nullable = false, columnDefinition = "TEXT")
    private String livenessProbeJson;

    @Column(name = "startup_probe_json", columnDefinition = "TEXT")
    private String startupProbeJson;

    @Column(name = "rolling_max_surge", nullable = false, length = 16)
    private String rollingMaxSurge = "1";

    @Column(name = "rolling_max_unavailable", nullable = false, length = 16)
    private String rollingMaxUnavailable = "0";

    @Column(name = "progress_deadline_seconds", nullable = false)
    private int progressDeadlineSeconds = 300;

    @Column(name = "deployment_timeout_seconds", nullable = false)
    private int deploymentTimeoutSeconds = 300;

    @Column(name = "read_only_root_filesystem", nullable = false)
    private boolean readOnlyRootFilesystem = true;

    @Column(name = "run_as_non_root", nullable = false)
    private boolean runAsNonRoot = true;

    @Column(name = "run_as_user", nullable = false)
    private long runAsUser = 10001L;

    @Column(name = "config_checksum_sha256", nullable = false, length = 64)
    private String configChecksumSha256;

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

    public int getReplicaCount() {
        return replicaCount;
    }

    public void setReplicaCount(int replicaCount) {
        this.replicaCount = replicaCount;
    }

    public int getContainerPort() {
        return containerPort;
    }

    public void setContainerPort(int containerPort) {
        this.containerPort = containerPort;
    }

    public int getServicePort() {
        return servicePort;
    }

    public void setServicePort(int servicePort) {
        this.servicePort = servicePort;
    }

    public String getCpuRequest() {
        return cpuRequest;
    }

    public void setCpuRequest(String cpuRequest) {
        this.cpuRequest = cpuRequest;
    }

    public String getCpuLimit() {
        return cpuLimit;
    }

    public void setCpuLimit(String cpuLimit) {
        this.cpuLimit = cpuLimit;
    }

    public String getMemoryRequest() {
        return memoryRequest;
    }

    public void setMemoryRequest(String memoryRequest) {
        this.memoryRequest = memoryRequest;
    }

    public String getMemoryLimit() {
        return memoryLimit;
    }

    public void setMemoryLimit(String memoryLimit) {
        this.memoryLimit = memoryLimit;
    }

    public String getReadinessProbeJson() {
        return readinessProbeJson;
    }

    public void setReadinessProbeJson(String readinessProbeJson) {
        this.readinessProbeJson = readinessProbeJson;
    }

    public String getLivenessProbeJson() {
        return livenessProbeJson;
    }

    public void setLivenessProbeJson(String livenessProbeJson) {
        this.livenessProbeJson = livenessProbeJson;
    }

    public String getStartupProbeJson() {
        return startupProbeJson;
    }

    public void setStartupProbeJson(String startupProbeJson) {
        this.startupProbeJson = startupProbeJson;
    }

    public String getRollingMaxSurge() {
        return rollingMaxSurge;
    }

    public void setRollingMaxSurge(String rollingMaxSurge) {
        this.rollingMaxSurge = rollingMaxSurge;
    }

    public String getRollingMaxUnavailable() {
        return rollingMaxUnavailable;
    }

    public void setRollingMaxUnavailable(String rollingMaxUnavailable) {
        this.rollingMaxUnavailable = rollingMaxUnavailable;
    }

    public int getProgressDeadlineSeconds() {
        return progressDeadlineSeconds;
    }

    public void setProgressDeadlineSeconds(int progressDeadlineSeconds) {
        this.progressDeadlineSeconds = progressDeadlineSeconds;
    }

    public int getDeploymentTimeoutSeconds() {
        return deploymentTimeoutSeconds;
    }

    public void setDeploymentTimeoutSeconds(int deploymentTimeoutSeconds) {
        this.deploymentTimeoutSeconds = deploymentTimeoutSeconds;
    }

    public boolean isReadOnlyRootFilesystem() {
        return readOnlyRootFilesystem;
    }

    public void setReadOnlyRootFilesystem(boolean readOnlyRootFilesystem) {
        this.readOnlyRootFilesystem = readOnlyRootFilesystem;
    }

    public boolean isRunAsNonRoot() {
        return runAsNonRoot;
    }

    public void setRunAsNonRoot(boolean runAsNonRoot) {
        this.runAsNonRoot = runAsNonRoot;
    }

    public long getRunAsUser() {
        return runAsUser;
    }

    public void setRunAsUser(long runAsUser) {
        this.runAsUser = runAsUser;
    }

    public String getConfigChecksumSha256() {
        return configChecksumSha256;
    }

    public void setConfigChecksumSha256(String configChecksumSha256) {
        this.configChecksumSha256 = configChecksumSha256;
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
