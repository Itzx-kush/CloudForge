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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Immutable AES-256-GCM encrypted version of a project environment secret.
 * Never serialized directly to API responses; only cryptographic metadata is exposed
 * to authorized workspace owners, and plaintext is recovered strictly for authorized
 * active worker leases.
 */
@Entity
@Table(name = "environment_secret_versions")
public class EnvironmentSecretVersionEntity extends BaseAuditableEntity {

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
    @JoinColumn(name = "secret_id", nullable = false)
    private EnvironmentSecretEntity secret;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "ciphertext", nullable = false, columnDefinition = "BYTEA")
    private byte[] ciphertext;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "nonce", nullable = false, columnDefinition = "BYTEA")
    private byte[] nonce;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "wrapped_dek", nullable = false, columnDefinition = "BYTEA")
    private byte[] wrappedDek;

    @Column(name = "kek_id", nullable = false, length = 64)
    private String kekId;

    @Column(name = "encryption_algorithm", nullable = false, length = 40)
    private String encryptionAlgorithm = "AES-256-GCM";

    @Column(name = "encryption_format_version", nullable = false)
    private int encryptionFormatVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SecretVersionStatus status = SecretVersionStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserEntity createdBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by_user_id")
    private UserEntity revokedBy;

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

    public EnvironmentSecretEntity getSecret() {
        return secret;
    }

    public void setSecret(EnvironmentSecretEntity secret) {
        this.secret = secret;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(byte[] ciphertext) {
        this.ciphertext = ciphertext;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    public byte[] getWrappedDek() {
        return wrappedDek;
    }

    public void setWrappedDek(byte[] wrappedDek) {
        this.wrappedDek = wrappedDek;
    }

    public String getKekId() {
        return kekId;
    }

    public void setKekId(String kekId) {
        this.kekId = kekId;
    }

    public String getEncryptionAlgorithm() {
        return encryptionAlgorithm;
    }

    public void setEncryptionAlgorithm(String encryptionAlgorithm) {
        this.encryptionAlgorithm = encryptionAlgorithm;
    }

    public int getEncryptionFormatVersion() {
        return encryptionFormatVersion;
    }

    public void setEncryptionFormatVersion(int encryptionFormatVersion) {
        this.encryptionFormatVersion = encryptionFormatVersion;
    }

    public SecretVersionStatus getStatus() {
        return status;
    }

    public void setStatus(SecretVersionStatus status) {
        this.status = status;
    }

    public UserEntity getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserEntity createdBy) {
        this.createdBy = createdBy;
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

    public Long getRowVersion() {
        return rowVersion;
    }
}
