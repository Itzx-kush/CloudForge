-- ============================================================================
-- CloudForge Phase 2 — V1: Identity, Workspaces, GitHub Apps, Projects & Audit
-- ============================================================================

CREATE TABLE users (
    id UUID PRIMARY KEY,
    github_user_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    email VARCHAR(255),
    avatar_url VARCHAR(512),
    profile_url VARCHAR(512),
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_users_github_user_id UNIQUE (github_user_id)
);

CREATE INDEX idx_users_username ON users (username);

CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    personal BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_workspaces_slug UNIQUE (slug),
    CONSTRAINT fk_workspaces_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_workspaces_created_by ON workspaces (created_by_user_id);

CREATE TABLE workspace_members (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_workspace_members_workspace_user UNIQUE (workspace_id, user_id),
    CONSTRAINT ck_workspace_members_role CHECK (role IN ('OWNER', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT fk_workspace_members_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_workspace_members_user ON workspace_members (user_id);
CREATE INDEX idx_workspace_members_workspace ON workspace_members (workspace_id);

CREATE TABLE github_installations (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    github_installation_id BIGINT NOT NULL,
    account_login VARCHAR(150) NOT NULL,
    account_type VARCHAR(50) NOT NULL,
    account_avatar_url VARCHAR(512),
    repository_selection VARCHAR(32) NOT NULL DEFAULT 'selected',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    installed_by_user_id UUID,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_github_installations_gh_id UNIQUE (github_installation_id),
    CONSTRAINT ck_github_installations_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT fk_github_installations_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_github_installations_user FOREIGN KEY (installed_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_github_installations_workspace ON github_installations (workspace_id);
CREATE INDEX idx_github_installations_status ON github_installations (status);

CREATE TABLE linked_repositories (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    github_installation_id UUID NOT NULL,
    github_repo_id BIGINT NOT NULL,
    owner_login VARCHAR(150) NOT NULL,
    name VARCHAR(200) NOT NULL,
    full_name VARCHAR(360) NOT NULL,
    default_branch VARCHAR(150) NOT NULL DEFAULT 'main',
    visibility VARCHAR(32) NOT NULL DEFAULT 'private',
    html_url VARCHAR(512),
    description VARCHAR(500),
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    connection_status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED',
    github_updated_at TIMESTAMP WITH TIME ZONE,
    last_synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_linked_repositories_workspace_gh_repo UNIQUE (workspace_id, github_repo_id),
    CONSTRAINT ck_linked_repositories_status CHECK (connection_status IN ('CONNECTED', 'SUSPENDED', 'DISCONNECTED')),
    CONSTRAINT fk_linked_repositories_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_linked_repositories_installation FOREIGN KEY (github_installation_id) REFERENCES github_installations (id) ON DELETE CASCADE
);

CREATE INDEX idx_linked_repositories_workspace ON linked_repositories (workspace_id);
CREATE INDEX idx_linked_repositories_installation ON linked_repositories (github_installation_id);
CREATE INDEX idx_linked_repositories_full_name ON linked_repositories (full_name);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    linked_repository_id UUID,
    default_branch VARCHAR(150) NOT NULL DEFAULT 'main',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by_user_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_projects_workspace_slug UNIQUE (workspace_id, slug),
    CONSTRAINT ck_projects_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ARCHIVED')),
    CONSTRAINT fk_projects_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_projects_linked_repository FOREIGN KEY (linked_repository_id) REFERENCES linked_repositories (id) ON DELETE SET NULL,
    CONSTRAINT fk_projects_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_projects_workspace ON projects (workspace_id);
CREATE INDEX idx_projects_linked_repository ON projects (linked_repository_id);
CREATE INDEX idx_projects_status ON projects (status);

CREATE TABLE project_environments (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(64) NOT NULL,
    environment_type VARCHAR(32) NOT NULL,
    branch_override VARCHAR(150),
    config_variables_json TEXT NOT NULL DEFAULT '{}',
    notes VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_project_environments_project_type UNIQUE (project_id, environment_type),
    CONSTRAINT ck_project_environments_type CHECK (environment_type IN ('DEVELOPMENT', 'STAGING', 'PRODUCTION')),
    CONSTRAINT fk_project_environments_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE INDEX idx_project_environments_project ON project_environments (project_id);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    workspace_id UUID,
    actor_user_id UUID,
    actor_username VARCHAR(100),
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(120),
    summary VARCHAR(500) NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    request_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_audit_events_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_events_workspace_created ON audit_events (workspace_id, created_at DESC);
CREATE INDEX idx_audit_events_actor ON audit_events (actor_user_id);
CREATE INDEX idx_audit_events_action ON audit_events (action);
