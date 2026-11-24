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
@Table(name = "github_workflow_runs")
public class GitHubWorkflowRunEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "linked_repository_id", nullable = false)
    private LinkedRepositoryEntity linkedRepository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id")
    private GitHubWorkflowEntity workflow;

    @Column(name = "github_run_id", nullable = false)
    private Long githubRunId;

    @Column(name = "github_workflow_id", nullable = false)
    private Long githubWorkflowId;

    @Column(name = "run_number", nullable = false)
    private Integer runNumber;

    @Column(name = "run_attempt", nullable = false)
    private Integer runAttempt = 1;

    @Column(name = "workflow_name", nullable = false, length = 255)
    private String workflowName;

    @Column(name = "display_title", length = 500)
    private String displayTitle;

    @Column(name = "event", nullable = false, length = 64)
    private String event;

    @Column(name = "head_branch", length = 255)
    private String headBranch;

    @Column(name = "head_sha", length = 64)
    private String headSha;

    @Column(name = "github_status", nullable = false, length = 64)
    private String githubStatus;

    @Column(name = "github_conclusion", length = 64)
    private String githubConclusion;

    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_status", nullable = false, length = 32)
    private WorkflowRunNormalizedStatus normalizedStatus = WorkflowRunNormalizedStatus.UNKNOWN;

    @Column(name = "actor_login", length = 150)
    private String actorLogin;

    @Column(name = "actor_avatar_url", length = 512)
    private String actorAvatarUrl;

    @Column(name = "html_url", length = 512)
    private String htmlUrl;

    @Column(name = "github_created_at")
    private Instant githubCreatedAt;

    @Column(name = "github_updated_at")
    private Instant githubUpdatedAt;

    @Column(name = "run_started_at")
    private Instant runStartedAt;

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

    public GitHubWorkflowEntity getWorkflow() {
        return workflow;
    }

    public void setWorkflow(GitHubWorkflowEntity workflow) {
        this.workflow = workflow;
    }

    public Long getGithubRunId() {
        return githubRunId;
    }

    public void setGithubRunId(Long githubRunId) {
        this.githubRunId = githubRunId;
    }

    public Long getGithubWorkflowId() {
        return githubWorkflowId;
    }

    public void setGithubWorkflowId(Long githubWorkflowId) {
        this.githubWorkflowId = githubWorkflowId;
    }

    public Integer getRunNumber() {
        return runNumber;
    }

    public void setRunNumber(Integer runNumber) {
        this.runNumber = runNumber;
    }

    public Integer getRunAttempt() {
        return runAttempt;
    }

    public void setRunAttempt(Integer runAttempt) {
        this.runAttempt = runAttempt;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public String getDisplayTitle() {
        return displayTitle;
    }

    public void setDisplayTitle(String displayTitle) {
        this.displayTitle = displayTitle;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getHeadBranch() {
        return headBranch;
    }

    public void setHeadBranch(String headBranch) {
        this.headBranch = headBranch;
    }

    public String getHeadSha() {
        return headSha;
    }

    public void setHeadSha(String headSha) {
        this.headSha = headSha;
    }

    public String getGithubStatus() {
        return githubStatus;
    }

    public void setGithubStatus(String githubStatus) {
        this.githubStatus = githubStatus;
    }

    public String getGithubConclusion() {
        return githubConclusion;
    }

    public void setGithubConclusion(String githubConclusion) {
        this.githubConclusion = githubConclusion;
    }

    public WorkflowRunNormalizedStatus getNormalizedStatus() {
        return normalizedStatus;
    }

    public void setNormalizedStatus(WorkflowRunNormalizedStatus normalizedStatus) {
        this.normalizedStatus = normalizedStatus;
    }

    public String getActorLogin() {
        return actorLogin;
    }

    public void setActorLogin(String actorLogin) {
        this.actorLogin = actorLogin;
    }

    public String getActorAvatarUrl() {
        return actorAvatarUrl;
    }

    public void setActorAvatarUrl(String actorAvatarUrl) {
        this.actorAvatarUrl = actorAvatarUrl;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
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

    public Instant getRunStartedAt() {
        return runStartedAt;
    }

    public void setRunStartedAt(Instant runStartedAt) {
        this.runStartedAt = runStartedAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }
}
