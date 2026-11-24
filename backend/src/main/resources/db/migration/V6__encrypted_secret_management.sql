-- ============================================================================
-- CloudForge Phase 3.4 — V6: Encrypted Secret Management & Secure Delivery
-- ============================================================================
-- Non-destructive migration preserving V1-V5 completely untouched.
-- Introduces envelope-encrypted environment secrets, immutable encrypted
-- secret versions, pipeline run pinned secret-version references, and
-- master key rotation tracking.
-- Never stores plaintext secret values or plaintext master encryption keys.

ALTER TABLE pipeline_definitions
    ADD COLUMN target_environment_id UUID;

ALTER TABLE pipeline_definitions
    ADD CONSTRAINT fk_pipeline_defs_target_env
        FOREIGN KEY (target_environment_id) REFERENCES project_environments (id) ON DELETE SET NULL;

ALTER TABLE pipeline_stage_definitions
    ADD COLUMN secret_bindings_json TEXT NOT NULL DEFAULT '[]';

ALTER TABLE pipeline_runs
    ADD COLUMN target_environment_id UUID;

ALTER TABLE pipeline_runs
    ADD COLUMN has_secret_bindings BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE pipeline_runs
    ADD CONSTRAINT fk_pipeline_runs_target_env
        FOREIGN KEY (target_environment_id) REFERENCES project_environments (id) ON DELETE SET NULL;

ALTER TABLE pipeline_stage_runs
    ADD COLUMN secret_bindings_json TEXT NOT NULL DEFAULT '[]';

ALTER TABLE pipeline_stage_runs
    ADD COLUMN has_secret_bindings BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE stage_execution_attempts
    ADD COLUMN secret_delivery_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE stage_execution_attempts
    ADD COLUMN last_secret_delivery_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE environment_secrets (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    secret_name VARCHAR(128) NOT NULL,
    name_lock_key VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    current_version_number INTEGER NOT NULL DEFAULT 1,
    current_version_id UUID,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by_user_id UUID,
    updated_by_user_id UUID,
    last_rotated_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    revoked_by_user_id UUID,
    deleted_at TIMESTAMP WITH TIME ZONE,
    deleted_by_user_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_env_secrets_env_lock_key UNIQUE (environment_id, name_lock_key),
    CONSTRAINT uq_env_secrets_scope UNIQUE (id, workspace_id, project_id, environment_id),
    CONSTRAINT ck_env_secrets_status CHECK (status IN ('ACTIVE', 'REVOKED', 'DELETED')),
    CONSTRAINT ck_env_secrets_ver_positive CHECK (current_version_number >= 1),
    CONSTRAINT fk_env_secrets_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_env_secrets_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_env_secrets_env FOREIGN KEY (environment_id) REFERENCES project_environments (id) ON DELETE CASCADE,
    CONSTRAINT fk_env_secrets_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_env_secrets_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_env_secrets_revoked_by FOREIGN KEY (revoked_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_env_secrets_deleted_by FOREIGN KEY (deleted_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_env_secrets_workspace_env ON environment_secrets (workspace_id, environment_id, status);
CREATE INDEX idx_env_secrets_project ON environment_secrets (project_id);
CREATE INDEX idx_env_secrets_env_name ON environment_secrets (environment_id, secret_name);

CREATE TABLE environment_secret_versions (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    secret_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    ciphertext BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    wrapped_dek BYTEA NOT NULL,
    kek_id VARCHAR(64) NOT NULL,
    encryption_algorithm VARCHAR(40) NOT NULL DEFAULT 'AES-256-GCM',
    encryption_format_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by_user_id UUID,
    revoked_at TIMESTAMP WITH TIME ZONE,
    revoked_by_user_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_env_secret_versions_secret_num UNIQUE (secret_id, version_number),
    CONSTRAINT uq_env_secret_versions_scope UNIQUE (id, workspace_id, project_id, environment_id, secret_id),
    CONSTRAINT ck_env_secret_versions_num_positive CHECK (version_number >= 1),
    CONSTRAINT ck_env_secret_versions_alg CHECK (encryption_algorithm = 'AES-256-GCM'),
    CONSTRAINT ck_env_secret_versions_fmt CHECK (encryption_format_version >= 1),
    CONSTRAINT ck_env_secret_versions_nonce_len CHECK (octet_length(nonce) = 12),
    CONSTRAINT ck_env_secret_versions_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT fk_env_secret_versions_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_env_secret_versions_secret_scope
        FOREIGN KEY (secret_id, workspace_id, project_id, environment_id)
        REFERENCES environment_secrets (id, workspace_id, project_id, environment_id) ON DELETE CASCADE,
    CONSTRAINT fk_env_secret_versions_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_env_secret_versions_revoked_by FOREIGN KEY (revoked_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_env_secret_versions_workspace ON environment_secret_versions (workspace_id);
CREATE INDEX idx_env_secret_versions_secret_ver ON environment_secret_versions (secret_id, version_number DESC);
CREATE INDEX idx_env_secret_versions_kek ON environment_secret_versions (kek_id, status);

CREATE TABLE pipeline_run_secret_references (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    stage_run_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    secret_id UUID NOT NULL,
    secret_version_id UUID NOT NULL,
    secret_name VARCHAR(128) NOT NULL,
    delivery_alias VARCHAR(128) NOT NULL,
    pinned_version_number INTEGER NOT NULL,
    delivery_count INTEGER NOT NULL DEFAULT 0,
    last_delivered_at TIMESTAMP WITH TIME ZONE,
    last_delivered_attempt_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_run_secret_refs_stage_alias UNIQUE (stage_run_id, delivery_alias),
    CONSTRAINT uq_run_secret_refs_stage_secret UNIQUE (stage_run_id, secret_id),
    CONSTRAINT ck_run_secret_refs_ver_positive CHECK (pinned_version_number >= 1),
    CONSTRAINT ck_run_secret_refs_deliv_nonneg CHECK (delivery_count >= 0),
    CONSTRAINT fk_run_secret_refs_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_secret_refs_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_secret_refs_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_secret_refs_stage FOREIGN KEY (stage_run_id) REFERENCES pipeline_stage_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_secret_refs_env FOREIGN KEY (environment_id) REFERENCES project_environments (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_secret_refs_version_scope
        FOREIGN KEY (secret_version_id, workspace_id, project_id, environment_id, secret_id)
        REFERENCES environment_secret_versions (id, workspace_id, project_id, environment_id, secret_id) ON DELETE CASCADE
);

CREATE INDEX idx_run_secret_refs_run ON pipeline_run_secret_references (pipeline_run_id);
CREATE INDEX idx_run_secret_refs_stage ON pipeline_run_secret_references (stage_run_id);
CREATE INDEX idx_run_secret_refs_secret ON pipeline_run_secret_references (secret_id);
CREATE INDEX idx_run_secret_refs_workspace ON pipeline_run_secret_references (workspace_id);

CREATE TABLE secret_key_rotation_runs (
    id UUID PRIMARY KEY,
    target_kek_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    total_candidates INTEGER NOT NULL DEFAULT 0,
    processed_count INTEGER NOT NULL DEFAULT 0,
    rewrapped_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    last_processed_version_id UUID,
    initiated_by VARCHAR(128) NOT NULL,
    error_summary VARCHAR(500),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_secret_key_rot_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'COMPLETED_WITH_ERRORS', 'FAILED')),
    CONSTRAINT ck_secret_key_rot_counts CHECK (
        total_candidates >= 0
        AND processed_count >= 0
        AND rewrapped_count >= 0
        AND skipped_count >= 0
        AND failed_count >= 0
    )
);

CREATE INDEX idx_secret_key_rot_status_started ON secret_key_rotation_runs (status, started_at DESC);
