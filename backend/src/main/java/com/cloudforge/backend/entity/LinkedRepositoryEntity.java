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
@Table(name = "linked_repositories")
public class LinkedRepositoryEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "github_installation_id", nullable = false)
    private GitHubInstallationEntity installation;

    @Column(name = "github_repo_id", nullable = false)
    private Long githubRepoId;

    @Column(name = "owner_login", nullable = false, length = 150)
    private String ownerLogin;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "full_name", nullable = false, length = 360)
    private String fullName;

    @Column(name = "default_branch", nullable = false, length = 150)
    private String defaultBranch = "main";

    @Column(name = "visibility", nullable = false, length = 32)
    private String visibility = "private";

    @Column(name = "html_url", length = 512)
    private String htmlUrl;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false, length = 32)
    private RepositoryConnectionStatus connectionStatus = RepositoryConnectionStatus.CONNECTED;

    @Column(name = "github_updated_at")
    private Instant githubUpdatedAt;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public void setWorkspace(WorkspaceEntity workspace) {
        this.workspace = workspace;
    }

    public GitHubInstallationEntity getInstallation() {
        return installation;
    }

    public void setInstallation(GitHubInstallationEntity installation) {
        this.installation = installation;
    }

    public Long getGithubRepoId() {
        return githubRepoId;
    }

    public void setGithubRepoId(Long githubRepoId) {
        this.githubRepoId = githubRepoId;
    }

    public String getOwnerLogin() {
        return ownerLogin;
    }

    public void setOwnerLogin(String ownerLogin) {
        this.ownerLogin = ownerLogin;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public RepositoryConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(RepositoryConnectionStatus connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public Instant getGithubUpdatedAt() {
        return githubUpdatedAt;
    }

    public void setGithubUpdatedAt(Instant githubUpdatedAt) {
        this.githubUpdatedAt = githubUpdatedAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }
}
