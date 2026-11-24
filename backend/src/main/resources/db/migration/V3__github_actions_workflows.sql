-- ============================================================================
-- CloudForge Phase 3.1 — V3: Read-Only GitHub Actions Workflows & Runs Schema
-- ============================================================================
-- Stores strictly non-sensitive GitHub Actions workflow and execution metadata
-- synchronized via least-privilege GitHub App installation tokens (actions:read).
-- Never stores GitHub tokens, cookies, repository secrets, or raw env vars.

CREATE TABLE github_workflows (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    linked_repository_id UUID NOT NULL,
    github_workflow_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    path VARCHAR(512) NOT NULL,
    state VARCHAR(64) NOT NULL DEFAULT 'active',
    html_url VARCHAR(512),
    badge_url VARCHAR(512),
    github_created_at TIMESTAMP WITH TIME ZONE,
    github_updated_at TIMESTAMP WITH TIME ZONE,
    last_synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_github_workflows_repo_gh_workflow UNIQUE (linked_repository_id, github_workflow_id),
    CONSTRAINT fk_github_workflows_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_github_workflows_repository FOREIGN KEY (linked_repository_id) REFERENCES linked_repositories (id) ON DELETE CASCADE
);

CREATE INDEX idx_github_workflows_workspace ON github_workflows (workspace_id);
CREATE INDEX idx_github_workflows_repository ON github_workflows (linked_repository_id);
CREATE INDEX idx_github_workflows_repo_state ON github_workflows (linked_repository_id, state);

CREATE TABLE github_workflow_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    linked_repository_id UUID NOT NULL,
    workflow_id UUID,
    github_run_id BIGINT NOT NULL,
    github_workflow_id BIGINT NOT NULL,
    run_number INTEGER NOT NULL,
    run_attempt INTEGER NOT NULL DEFAULT 1,
    workflow_name VARCHAR(255) NOT NULL,
    display_title VARCHAR(500),
    event VARCHAR(64) NOT NULL,
    head_branch VARCHAR(255),
    head_sha VARCHAR(64),
    github_status VARCHAR(64) NOT NULL,
    github_conclusion VARCHAR(64),
    normalized_status VARCHAR(32) NOT NULL,
    actor_login VARCHAR(150),
    actor_avatar_url VARCHAR(512),
    html_url VARCHAR(512),
    github_created_at TIMESTAMP WITH TIME ZONE,
    github_updated_at TIMESTAMP WITH TIME ZONE,
    run_started_at TIMESTAMP WITH TIME ZONE,
    last_synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_github_workflow_runs_repo_run_attempt UNIQUE (linked_repository_id, github_run_id, run_attempt),
    CONSTRAINT ck_github_workflow_runs_attempt_positive CHECK (run_attempt >= 1),
    CONSTRAINT ck_github_workflow_runs_number_non_negative CHECK (run_number >= 0),
    CONSTRAINT ck_github_workflow_runs_normalized_status CHECK (
        normalized_status IN (
            'QUEUED',
            'IN_PROGRESS',
            'SUCCESS',
            'FAILURE',
            'CANCELLED',
            'TIMED_OUT',
            'SKIPPED',
            'UNKNOWN'
        )
    ),
    CONSTRAINT fk_github_workflow_runs_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_github_workflow_runs_repository FOREIGN KEY (linked_repository_id) REFERENCES linked_repositories (id) ON DELETE CASCADE,
    CONSTRAINT fk_github_workflow_runs_workflow FOREIGN KEY (workflow_id) REFERENCES github_workflows (id) ON DELETE SET NULL
);

CREATE INDEX idx_github_workflow_runs_workspace_created ON github_workflow_runs (workspace_id, github_created_at DESC);
CREATE INDEX idx_github_workflow_runs_repo_created ON github_workflow_runs (linked_repository_id, github_created_at DESC);
CREATE INDEX idx_github_workflow_runs_workflow ON github_workflow_runs (workflow_id, github_created_at DESC);
CREATE INDEX idx_github_workflow_runs_workspace_status ON github_workflow_runs (workspace_id, normalized_status);
CREATE INDEX idx_github_workflow_runs_repo_run ON github_workflow_runs (linked_repository_id, github_run_id);
