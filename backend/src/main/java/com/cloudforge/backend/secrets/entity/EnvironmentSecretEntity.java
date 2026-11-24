package com.cloudforge.backend.secrets.entity;

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
import java.util.UUID;

/**
 * Logical environment secret metadata record scoped to a single workspace, project,
 * and project environment. Never holds plaintext or encrypted secret material directly;
 * versions are stored in {@link EnvironmentSecretVersionEntity}.
 */
@Entity
@Table(name = "environment_secrets")
public class EnvironmentSecretEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false)
    private ProjectEnvironmentEntity environment;

    @Column(name = "secret_name", nullable = false, length = 128)
    private String secretName;

    @Column(name = "name_lock_key", nullable = false, length = 200)
    private String nameLockKey;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "current_version_number", nullable = false)
    private int currentVersionNumber = 1;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SecretLifecycleStatus status = SecretLifecycleStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserEntity createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private UserEntity updatedBy;

    @Column(name = "last_rotated_at")
    private Instant lastRotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by_user_id")
    private UserEntity revokedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by_user_id")
    private UserEntity deletedBy;

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

    public String getSecretName() {
        return secretName;
    }

    public void setSecretName(String secretName) {
        this.secretName = secretName;
    }

    public String getNameLockKey() {
        return nameLockKey;
    }

    public void setNameLockKey(String nameLockKey) {
        this.nameLockKey = nameLockKey;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getCurrentVersionNumber() {
        return currentVersionNumber;
    }

    public void setCurrentVersionNumber(int currentVersionNumber) {
        this.currentVersionNumber = currentVersionNumber;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(UUID currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public SecretLifecycleStatus getStatus() {
        return status;
    }

    public void setStatus(SecretLifecycleStatus status) {
        this.status = status;
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

    public Instant getLastRotatedAt() {
        return lastRotatedAt;
    }

    public void setLastRotatedAt(Instant lastRotatedAt) {
        this.lastRotatedAt = lastRotatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public UserEntity getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(UserEntity revokedBy) {
        this.revokedBy = revokedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public UserEntity getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(UserEntity deletedBy) {
        this.deletedBy = deletedBy;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(Long rowVersion) {
        this.rowVersion = rowVersion;
    }
}
