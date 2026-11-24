-- ============================================================================
-- CloudForge Phase 3.2 — V4: Persistent Pipeline Orchestration Engine Schema
-- ============================================================================
-- Stores workspace-scoped CloudForge pipeline definitions, stage DAG definitions,
-- immutable execution run records, stage-run state machines, lifecycle event
-- history, and restart-safe idempotency keys.
-- Never stores plaintext credentials or executable shell commands.

CREATE TABLE pipeline_definitions (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    linked_repository_id UUID,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    definition_version INTEGER NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_by_user_id UUID,
    updated_by_user_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_pipeline_definitions_project_slug UNIQUE (project_id, slug),
    CONSTRAINT ck_pipeline_definitions_version_positive CHECK (definition_version >= 1),
    CONSTRAINT fk_pipeline_definitions_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_definitions_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_definitions_repository FOREIGN KEY (linked_repository_id) REFERENCES linked_repositories (id) ON DELETE SET NULL,
    CONSTRAINT fk_pipeline_definitions_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_pipeline_definitions_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_pipeline_definitions_workspace ON pipeline_definitions (workspace_id);
CREATE INDEX idx_pipeline_definitions_project ON pipeline_definitions (project_id);
CREATE INDEX idx_pipeline_definitions_workspace_enabled ON pipeline_definitions (workspace_id, enabled);

CREATE TABLE pipeline_stage_definitions (
    id UUID PRIMARY KEY,
    pipeline_definition_id UUID NOT NULL,
    stage_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    stage_type VARCHAR(32) NOT NULL,
    execution_order INTEGER NOT NULL,
    depends_on_json TEXT NOT NULL DEFAULT '[]',
    timeout_seconds INTEGER NOT NULL DEFAULT 600,
    max_retries INTEGER NOT NULL DEFAULT 0,
    allow_failure BOOLEAN NOT NULL DEFAULT FALSE,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_pipeline_stage_defs_def_key UNIQUE (pipeline_definition_id, stage_key),
    CONSTRAINT ck_pipeline_stage_defs_type CHECK (
        stage_type IN (
            'SOURCE_PREP',
            'BUILD',
            'TEST',
            'SECURITY_SCAN',
            'CONTAINER_PACKAGE',
            'DEPLOYMENT'
        )
    ),
    CONSTRAINT ck_pipeline_stage_defs_timeout CHECK (timeout_seconds >= 30 AND timeout_seconds <= 7200),
    CONSTRAINT ck_pipeline_stage_defs_retries CHECK (max_retries >= 0 AND max_retries <= 5),
    CONSTRAINT ck_pipeline_stage_defs_order CHECK (execution_order >= 0),
    CONSTRAINT fk_pipeline_stage_defs_definition FOREIGN KEY (pipeline_definition_id) REFERENCES pipeline_definitions (id) ON DELETE CASCADE
);

CREATE INDEX idx_pipeline_stage_defs_definition ON pipeline_stage_definitions (pipeline_definition_id, execution_order);

CREATE TABLE pipeline_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    pipeline_definition_id UUID,
    linked_repository_id UUID,
    run_number INTEGER NOT NULL,
    definition_name VARCHAR(120) NOT NULL,
    definition_version INTEGER NOT NULL,
    definition_snapshot_json TEXT NOT NULL,
    trigger_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    target_branch VARCHAR(150),
    commit_sha VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    status_reason VARCHAR(500),
    requested_by_user_id UUID,
    cancelled_by_user_id UUID,
    queued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_pipeline_runs_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_pipeline_runs_trigger CHECK (trigger_type IN ('MANUAL', 'API', 'SCHEDULE', 'WEBHOOK')),
    CONSTRAINT ck_pipeline_runs_number_positive CHECK (run_number >= 1),
    CONSTRAINT ck_pipeline_runs_def_version_positive CHECK (definition_version >= 1),
    CONSTRAINT fk_pipeline_runs_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_runs_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_runs_definition FOREIGN KEY (pipeline_definition_id) REFERENCES pipeline_definitions (id) ON DELETE SET NULL,
    CONSTRAINT fk_pipeline_runs_repository FOREIGN KEY (linked_repository_id) REFERENCES linked_repositories (id) ON DELETE SET NULL,
    CONSTRAINT fk_pipeline_runs_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_pipeline_runs_cancelled_by FOREIGN KEY (cancelled_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_pipeline_runs_workspace_created ON pipeline_runs (workspace_id, created_at DESC);
CREATE INDEX idx_pipeline_runs_project_created ON pipeline_runs (project_id, created_at DESC);
CREATE INDEX idx_pipeline_runs_definition_created ON pipeline_runs (pipeline_definition_id, created_at DESC);
CREATE INDEX idx_pipeline_runs_workspace_status ON pipeline_runs (workspace_id, status);

CREATE TABLE pipeline_stage_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    stage_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    stage_type VARCHAR(32) NOT NULL,
    execution_order INTEGER NOT NULL,
    depends_on_json TEXT NOT NULL DEFAULT '[]',
    timeout_seconds INTEGER NOT NULL DEFAULT 600,
    max_retries INTEGER NOT NULL DEFAULT 0,
    attempt_number INTEGER NOT NULL DEFAULT 0,
    allow_failure BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL,
    status_reason VARCHAR(500),
    assigned_worker_id VARCHAR(128),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    ready_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_pipeline_stage_runs_run_key UNIQUE (pipeline_run_id, stage_key),
    CONSTRAINT ck_pipeline_stage_runs_status CHECK (
        status IN ('PENDING', 'READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')
    ),
    CONSTRAINT ck_pipeline_stage_runs_type CHECK (
        stage_type IN (
            'SOURCE_PREP',
            'BUILD',
            'TEST',
            'SECURITY_SCAN',
            'CONTAINER_PACKAGE',
            'DEPLOYMENT'
        )
    ),
    CONSTRAINT ck_pipeline_stage_runs_attempt CHECK (attempt_number >= 0),
    CONSTRAINT ck_pipeline_stage_runs_timeout CHECK (timeout_seconds >= 30 AND timeout_seconds <= 7200),
    CONSTRAINT ck_pipeline_stage_runs_retries CHECK (max_retries >= 0 AND max_retries <= 5),
    CONSTRAINT fk_pipeline_stage_runs_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_stage_runs_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE
);

CREATE INDEX idx_pipeline_stage_runs_run_order ON pipeline_stage_runs (pipeline_run_id, execution_order);
CREATE INDEX idx_pipeline_stage_runs_workspace_status ON pipeline_stage_runs (workspace_id, status);

CREATE TABLE pipeline_run_events (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    stage_run_id UUID,
    stage_key VARCHAR(64),
    event_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_user_id UUID,
    actor_username VARCHAR(100),
    message VARCHAR(500) NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_pipeline_run_events_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_run_events_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_run_events_stage FOREIGN KEY (stage_run_id) REFERENCES pipeline_stage_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_run_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_pipeline_run_events_run_occurred ON pipeline_run_events (pipeline_run_id, occurred_at ASC, created_at ASC);
CREATE INDEX idx_pipeline_run_events_workspace ON pipeline_run_events (workspace_id);

CREATE TABLE pipeline_idempotency_keys (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    pipeline_run_id UUID,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_pipeline_idempotency_scope UNIQUE (workspace_id, actor_user_id, operation, idempotency_key),
    CONSTRAINT fk_pipeline_idempotency_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_idempotency_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_idempotency_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE
);

CREATE INDEX idx_pipeline_idempotency_expires ON pipeline_idempotency_keys (expires_at);
CREATE INDEX idx_pipeline_idempotency_run ON pipeline_idempotency_keys (pipeline_run_id);
