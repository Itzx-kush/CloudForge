-- ============================================================================
-- CloudForge Phase 3.5B — V8: OCI Container Builds, Registry Connections &
--                             Immutable Artifact Management
-- ============================================================================
-- Non-destructive migration preserving V1-V7 completely untouched.
-- Adds OCI_BUILDKIT worker adapter capability, OCI failure categories,
-- workspace-scoped OCI registry connections (encrypted secret references only,
-- never plaintext credentials), bounded source/OCI handoffs, persistent build
-- records, immutable digest-verified image artifacts, publish attempts, and
-- chronological artifact lifecycle events.

ALTER TABLE worker_nodes DROP CONSTRAINT ck_worker_nodes_adapter;
ALTER TABLE worker_nodes ADD CONSTRAINT ck_worker_nodes_adapter
    CHECK (adapter_type IN ('DOCKER_ISOLATED', 'GITHUB_SOURCE', 'OCI_BUILDKIT', 'MOCK_TEST'));

ALTER TABLE stage_execution_attempts DROP CONSTRAINT ck_stage_attempts_failure_category;
ALTER TABLE stage_execution_attempts ADD CONSTRAINT ck_stage_attempts_failure_category
    CHECK (
        failure_category IN (
            'NONE',
            'INFRASTRUCTURE_FAILURE',
            'WORKER_CRASH',
            'LEASE_EXPIRED',
            'EXECUTION_TIMEOUT',
            'OPERATION_FAILURE',
            'CANCELLED',
            'UNSUPPORTED_OPERATION',
            'DOCKERFILE_POLICY_VIOLATION',
            'SOURCE_CONTEXT_INVALID',
            'BUILD_FAILED',
            'REGISTRY_AUTH_FAILED',
            'REGISTRY_UNAVAILABLE',
            'DIGEST_VERIFICATION_FAILED'
        )
    );

CREATE TABLE oci_registry_connections (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    registry_host VARCHAR(255) NOT NULL,
    scheme VARCHAR(8) NOT NULL DEFAULT 'https',
    registry_type VARCHAR(32) NOT NULL DEFAULT 'OCI_GENERIC',
    allowed_namespace VARCHAR(128) NOT NULL,
    credential_project_id UUID,
    credential_environment_id UUID,
    username_secret_id UUID,
    password_secret_id UUID,
    allow_insecure_local BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(500),
    created_by_user_id UUID,
    updated_by_user_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_oci_reg_conn_ws_slug UNIQUE (workspace_id, slug),
    CONSTRAINT ck_oci_reg_conn_scheme CHECK (scheme IN ('https', 'http')),
    CONSTRAINT ck_oci_reg_conn_type CHECK (registry_type IN ('OCI_GENERIC', 'GHCR', 'LOCAL_DEV')),
    CONSTRAINT ck_oci_reg_conn_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_oci_reg_conn_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_reg_conn_cred_proj FOREIGN KEY (credential_project_id) REFERENCES projects (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_reg_conn_cred_env FOREIGN KEY (credential_environment_id) REFERENCES project_environments (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_reg_conn_user_sec FOREIGN KEY (username_secret_id) REFERENCES environment_secrets (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_reg_conn_pass_sec FOREIGN KEY (password_secret_id) REFERENCES environment_secrets (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_reg_conn_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_reg_conn_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_oci_reg_conn_ws_status ON oci_registry_connections (workspace_id, status);
CREATE INDEX idx_oci_reg_conn_ws_host ON oci_registry_connections (workspace_id, registry_host);

CREATE TABLE oci_source_handoffs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    source_stage_run_id UUID NOT NULL,
    source_attempt_id UUID NOT NULL,
    handoff_type VARCHAR(24) NOT NULL,
    github_repository_id BIGINT NOT NULL,
    repository_full_name VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    content_checksum_sha256 VARCHAR(64) NOT NULL,
    archive_size_bytes BIGINT NOT NULL,
    extracted_bytes BIGINT NOT NULL DEFAULT 0,
    file_count INTEGER NOT NULL DEFAULT 0,
    storage_token VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    consumed_by_attempt_id UUID,
    cleaned_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_oci_handoffs_attempt_type UNIQUE (source_attempt_id, handoff_type),
    CONSTRAINT ck_oci_handoffs_type CHECK (handoff_type IN ('SOURCE_CONTEXT', 'OCI_IMAGE_LAYOUT')),
    CONSTRAINT ck_oci_handoffs_status CHECK (status IN ('AVAILABLE', 'CONSUMED', 'EXPIRED', 'CLEANED')),
    CONSTRAINT ck_oci_handoffs_size_nonneg CHECK (archive_size_bytes >= 0 AND extracted_bytes >= 0 AND file_count >= 0),
    CONSTRAINT fk_oci_handoffs_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_handoffs_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_handoffs_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_handoffs_stage FOREIGN KEY (source_stage_run_id) REFERENCES pipeline_stage_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_handoffs_attempt FOREIGN KEY (source_attempt_id) REFERENCES stage_execution_attempts (id) ON DELETE CASCADE
);

CREATE INDEX idx_oci_handoffs_run_type_status ON oci_source_handoffs (pipeline_run_id, handoff_type, status);
CREATE INDEX idx_oci_handoffs_status_expires ON oci_source_handoffs (status, expires_at);

CREATE TABLE oci_build_records (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    stage_run_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    linked_repository_id UUID,
    github_repository_id BIGINT NOT NULL,
    repository_full_name VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    source_handoff_id UUID,
    source_checksum_sha256 VARCHAR(64),
    registry_connection_id UUID,
    dockerfile_path VARCHAR(255) NOT NULL,
    build_context_path VARCHAR(255) NOT NULL,
    target_image_name VARCHAR(255) NOT NULL,
    target_tag VARCHAR(128),
    target_platform VARCHAR(64) NOT NULL,
    build_mode VARCHAR(32) NOT NULL,
    config_checksum_sha256 VARCHAR(64) NOT NULL,
    config_snapshot_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    built_manifest_digest VARCHAR(128),
    image_media_type VARCHAR(128),
    image_size_bytes BIGINT,
    oci_handoff_id UUID,
    worker_id VARCHAR(128) NOT NULL,
    failure_category VARCHAR(40),
    error_summary VARCHAR(500),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_oci_build_records_attempt UNIQUE (attempt_id),
    CONSTRAINT ck_oci_build_records_mode CHECK (build_mode IN ('ROOTLESS_BUILDKIT', 'LOCAL_TRUSTED_BUILDKIT')),
    CONSTRAINT ck_oci_build_records_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    CONSTRAINT fk_oci_build_records_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_build_records_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_build_records_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_build_records_stage FOREIGN KEY (stage_run_id) REFERENCES pipeline_stage_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_build_records_attempt FOREIGN KEY (attempt_id) REFERENCES stage_execution_attempts (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_build_records_repo FOREIGN KEY (linked_repository_id) REFERENCES linked_repositories (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_build_records_src_handoff FOREIGN KEY (source_handoff_id) REFERENCES oci_source_handoffs (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_build_records_oci_handoff FOREIGN KEY (oci_handoff_id) REFERENCES oci_source_handoffs (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_build_records_registry FOREIGN KEY (registry_connection_id) REFERENCES oci_registry_connections (id) ON DELETE SET NULL
);

CREATE INDEX idx_oci_build_records_ws_created ON oci_build_records (workspace_id, created_at DESC);
CREATE INDEX idx_oci_build_records_proj_created ON oci_build_records (project_id, created_at DESC);
CREATE INDEX idx_oci_build_records_run_created ON oci_build_records (pipeline_run_id, created_at DESC);

CREATE TABLE oci_image_artifacts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    stage_run_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    build_record_id UUID,
    registry_connection_id UUID,
    linked_repository_id UUID,
    github_repository_id BIGINT NOT NULL,
    repository_full_name VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    registry_host VARCHAR(255) NOT NULL,
    image_repository VARCHAR(255) NOT NULL,
    image_tag VARCHAR(128),
    manifest_digest VARCHAR(128) NOT NULL,
    immutable_reference VARCHAR(512) NOT NULL,
    image_media_type VARCHAR(128) NOT NULL,
    platform VARCHAR(64) NOT NULL,
    image_size_bytes BIGINT,
    verification_status VARCHAR(32) NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    retention_policy VARCHAR(32) NOT NULL DEFAULT 'RETAIN_METADATA',
    error_summary VARCHAR(500),
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_oci_artifacts_run_repo_digest UNIQUE (workspace_id, pipeline_run_id, registry_host, image_repository, manifest_digest),
    CONSTRAINT ck_oci_artifacts_verification CHECK (verification_status IN ('PENDING', 'VERIFIED', 'VERIFICATION_FAILED')),
    CONSTRAINT ck_oci_artifacts_retention CHECK (retention_policy IN ('RETAIN_METADATA', 'REFERENCED_PROTECTED')),
    CONSTRAINT fk_oci_artifacts_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_artifacts_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_artifacts_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_artifacts_stage FOREIGN KEY (stage_run_id) REFERENCES pipeline_stage_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_artifacts_attempt FOREIGN KEY (attempt_id) REFERENCES stage_execution_attempts (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_artifacts_build FOREIGN KEY (build_record_id) REFERENCES oci_build_records (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_artifacts_registry FOREIGN KEY (registry_connection_id) REFERENCES oci_registry_connections (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_artifacts_repo FOREIGN KEY (linked_repository_id) REFERENCES linked_repositories (id) ON DELETE SET NULL
);

CREATE INDEX idx_oci_artifacts_ws_published ON oci_image_artifacts (workspace_id, published_at DESC);
CREATE INDEX idx_oci_artifacts_proj_published ON oci_image_artifacts (project_id, published_at DESC);
CREATE INDEX idx_oci_artifacts_run_published ON oci_image_artifacts (pipeline_run_id, published_at DESC);
CREATE INDEX idx_oci_artifacts_ws_digest ON oci_image_artifacts (workspace_id, manifest_digest);

CREATE TABLE oci_publish_attempts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    stage_run_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    build_record_id UUID,
    registry_connection_id UUID,
    artifact_id UUID,
    worker_id VARCHAR(128) NOT NULL,
    registry_host VARCHAR(255) NOT NULL,
    image_repository VARCHAR(255) NOT NULL,
    image_tag VARCHAR(128),
    expected_digest VARCHAR(128),
    reported_digest VARCHAR(128),
    verified_digest VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    failure_category VARCHAR(40),
    error_summary VARCHAR(500),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_oci_publish_attempts_attempt UNIQUE (attempt_id),
    CONSTRAINT ck_oci_publish_attempts_status CHECK (status IN ('STARTED', 'PUSHED', 'VERIFIED', 'FAILED', 'CANCELLED', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT fk_oci_publish_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_publish_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_publish_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_publish_stage FOREIGN KEY (stage_run_id) REFERENCES pipeline_stage_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_publish_attempt FOREIGN KEY (attempt_id) REFERENCES stage_execution_attempts (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_publish_build FOREIGN KEY (build_record_id) REFERENCES oci_build_records (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_publish_registry FOREIGN KEY (registry_connection_id) REFERENCES oci_registry_connections (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_publish_artifact FOREIGN KEY (artifact_id) REFERENCES oci_image_artifacts (id) ON DELETE SET NULL
);

CREATE INDEX idx_oci_publish_ws_created ON oci_publish_attempts (workspace_id, created_at DESC);
CREATE INDEX idx_oci_publish_run_created ON oci_publish_attempts (pipeline_run_id, created_at DESC);

CREATE TABLE oci_artifact_events (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    build_record_id UUID,
    artifact_id UUID,
    publish_attempt_id UUID,
    event_type VARCHAR(48) NOT NULL,
    actor_label VARCHAR(128) NOT NULL,
    message VARCHAR(500) NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_oci_events_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_events_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_events_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_oci_events_build FOREIGN KEY (build_record_id) REFERENCES oci_build_records (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_events_artifact FOREIGN KEY (artifact_id) REFERENCES oci_image_artifacts (id) ON DELETE SET NULL,
    CONSTRAINT fk_oci_events_publish FOREIGN KEY (publish_attempt_id) REFERENCES oci_publish_attempts (id) ON DELETE SET NULL
);

CREATE INDEX idx_oci_events_ws_occurred ON oci_artifact_events (workspace_id, occurred_at DESC);
CREATE INDEX idx_oci_events_artifact_occurred ON oci_artifact_events (artifact_id, occurred_at ASC);
CREATE INDEX idx_oci_events_run_occurred ON oci_artifact_events (pipeline_run_id, occurred_at ASC);
