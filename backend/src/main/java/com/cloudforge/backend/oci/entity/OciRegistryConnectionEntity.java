package com.cloudforge.backend.oci.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
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

@Entity
@Table(name = "oci_registry_connections")
public class OciRegistryConnectionEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "registry_host", nullable = false, length = 255)
    private String registryHost;

    @Column(name = "scheme", nullable = false, length = 8)
    private String scheme = "https";

    @Enumerated(EnumType.STRING)
    @Column(name = "registry_type", nullable = false, length = 32)
    private OciRegistryType registryType = OciRegistryType.OCI_GENERIC;

    @Column(name = "allowed_namespace", nullable = false, length = 128)
    private String allowedNamespace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credential_project_id")
    private ProjectEntity credentialProject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credential_environment_id")
    private ProjectEnvironmentEntity credentialEnvironment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "username_secret_id")
    private EnvironmentSecretEntity usernameSecret;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "password_secret_id")
    private EnvironmentSecretEntity passwordSecret;

    @Column(name = "allow_insecure_local", nullable = false)
    private boolean allowInsecureLocal = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OciRegistryStatus status = OciRegistryStatus.ACTIVE;

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

    public String getRegistryHost() {
        return registryHost;
    }

    public void setRegistryHost(String registryHost) {
        this.registryHost = registryHost;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public OciRegistryType getRegistryType() {
        return registryType;
    }

    public void setRegistryType(OciRegistryType registryType) {
        this.registryType = registryType;
    }

    public String getAllowedNamespace() {
        return allowedNamespace;
    }

    public void setAllowedNamespace(String allowedNamespace) {
        this.allowedNamespace = allowedNamespace;
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

    public EnvironmentSecretEntity getUsernameSecret() {
        return usernameSecret;
    }

    public void setUsernameSecret(EnvironmentSecretEntity usernameSecret) {
        this.usernameSecret = usernameSecret;
    }

    public EnvironmentSecretEntity getPasswordSecret() {
        return passwordSecret;
    }

    public void setPasswordSecret(EnvironmentSecretEntity passwordSecret) {
        this.passwordSecret = passwordSecret;
    }

    public boolean isAllowInsecureLocal() {
        return allowInsecureLocal;
    }

    public void setAllowInsecureLocal(boolean allowInsecureLocal) {
        this.allowInsecureLocal = allowInsecureLocal;
    }

    public OciRegistryStatus getStatus() {
        return status;
    }

    public void setStatus(OciRegistryStatus status) {
        this.status = status;
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
