package com.cloudforge.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "github_installations")
public class GitHubInstallationEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @Column(name = "github_installation_id", nullable = false, unique = true)
    private Long githubInstallationId;

    @Column(name = "account_login", nullable = false, length = 150)
    private String accountLogin;

    @Column(name = "account_type", nullable = false, length = 50)
    private String accountType;

    @Column(name = "account_avatar_url", length = 512)
    private String accountAvatarUrl;

    @Column(name = "repository_selection", nullable = false, length = 32)
    private String repositorySelection = "selected";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private InstallationStatus status = InstallationStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installed_by_user_id")
    private UserEntity installedBy;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public void setWorkspace(WorkspaceEntity workspace) {
        this.workspace = workspace;
    }

    public Long getGithubInstallationId() {
        return githubInstallationId;
    }

    public void setGithubInstallationId(Long githubInstallationId) {
        this.githubInstallationId = githubInstallationId;
    }

    public String getAccountLogin() {
        return accountLogin;
    }

    public void setAccountLogin(String accountLogin) {
        this.accountLogin = accountLogin;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public String getAccountAvatarUrl() {
        return accountAvatarUrl;
    }

    public void setAccountAvatarUrl(String accountAvatarUrl) {
        this.accountAvatarUrl = accountAvatarUrl;
    }

    public String getRepositorySelection() {
        return repositorySelection;
    }

    public void setRepositorySelection(String repositorySelection) {
        this.repositorySelection = repositorySelection;
    }

    public InstallationStatus getStatus() {
        return status;
    }

    public void setStatus(InstallationStatus status) {
        this.status = status;
    }

    public UserEntity getInstalledBy() {
        return installedBy;
    }

    public void setInstalledBy(UserEntity installedBy) {
        this.installedBy = installedBy;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }
}
