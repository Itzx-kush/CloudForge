package com.cloudforge.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "github_workflows")
public class GitHubWorkflowEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "linked_repository_id", nullable = false)
    private LinkedRepositoryEntity linkedRepository;

    @Column(name = "github_workflow_id", nullable = false)
    private Long githubWorkflowId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "path", nullable = false, length = 512)
    private String path;

    @Column(name = "state", nullable = false, length = 64)
    private String state = "active";

    @Column(name = "html_url", length = 512)
    private String htmlUrl;

    @Column(name = "badge_url", length = 512)
    private String badgeUrl;

    @Column(name = "github_created_at")
    private Instant githubCreatedAt;

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

    public LinkedRepositoryEntity getLinkedRepository() {
        return linkedRepository;
    }

    public void setLinkedRepository(LinkedRepositoryEntity linkedRepository) {
        this.linkedRepository = linkedRepository;
    }

    public Long getGithubWorkflowId() {
        return githubWorkflowId;
    }

    public void setGithubWorkflowId(Long githubWorkflowId) {
        this.githubWorkflowId = githubWorkflowId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }

    public String getBadgeUrl() {
        return badgeUrl;
    }

    public void setBadgeUrl(String badgeUrl) {
        this.badgeUrl = badgeUrl;
    }

    public Instant getGithubCreatedAt() {
        return githubCreatedAt;
    }

    public void setGithubCreatedAt(Instant githubCreatedAt) {
        this.githubCreatedAt = githubCreatedAt;
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
