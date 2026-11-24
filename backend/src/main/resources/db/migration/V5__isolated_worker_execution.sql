-- ============================================================================
-- CloudForge Phase 3.3 — V5: Isolated Build Workers & Secure Execution Schema
-- ============================================================================
-- Extends Phase 3.2 orchestration tables non-destructively and introduces
-- trusted worker registry, hashed worker credential metadata, fenced stage
-- execution attempts/leases, and bounded sanitized execution logs.
-- Never stores plaintext worker secrets, OAuth tokens, or GitHub App keys.

ALTER TABLE pipeline_runs
    ADD COLUMN cancellation_requested_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE pipeline_runs
    ADD COLUMN cancellation_reason VARCHAR(500);

ALTER TABLE pipeline_stage_runs
    ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}';

ALTER TABLE pipeline_stage_runs
    ADD COLUMN allowed_operation VARCHAR(64);

ALTER TABLE pipeline_stage_runs
    ADD COLUMN active_lease_token VARCHAR(64);

ALTER TABLE pipeline_stage_runs
    ADD COLUMN fencing_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE pipeline_stage_runs
    ADD COLUMN next_retry_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE pipeline_stage_runs
    ADD COLUMN cancellation_requested_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_pipeline_stage_runs_ready_schedule
    ON pipeline_stage_runs (status, next_retry_at, ready_at);

CREATE TABLE worker_nodes (
    id UUID PRIMARY KEY,
    worker_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    adapter_type VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    supported_operations_json TEXT NOT NULL DEFAULT '[]',
    max_concurrency INTEGER NOT NULL DEFAULT 1,
    active_assignments INTEGER NOT NULL DEFAULT 0,
    credential_key_id VARCHAR(64),
    host_label VARCHAR(120),
    agent_version VARCHAR(64),
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    offline_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_worker_nodes_worker_id UNIQUE (worker_id),
    CONSTRAINT ck_worker_nodes_adapter CHECK (adapter_type IN ('DOCKER_ISOLATED', 'MOCK_TEST')),
    CONSTRAINT ck_worker_nodes_status CHECK (status IN ('AVAILABLE', 'BUSY', 'DRAINING', 'OFFLINE')),
    CONSTRAINT ck_worker_nodes_concurrency CHECK (max_concurrency >= 1 AND max_concurrency <= 16),
    CONSTRAINT ck_worker_nodes_active CHECK (active_assignments >= 0)
);

CREATE INDEX idx_worker_nodes_status_heartbeat ON worker_nodes (status, last_heartbeat_at);

CREATE TABLE worker_credentials (
    id UUID PRIMARY KEY,
    worker_id VARCHAR(128) NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_worker_credentials_key_id UNIQUE (key_id),
    CONSTRAINT ck_worker_credentials_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX idx_worker_credentials_worker_status ON worker_credentials (worker_id, status);

CREATE TABLE stage_execution_attempts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    stage_run_id UUID NOT NULL,
    worker_id VARCHAR(128) NOT NULL,
    attempt_number INTEGER NOT NULL,
    fencing_generation BIGINT NOT NULL,
    lease_token VARCHAR(64) NOT NULL,
    allowed_operation VARCHAR(64) NOT NULL,
    idempotent_operation BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL,
    failure_category VARCHAR(40) NOT NULL DEFAULT 'NONE',
    exit_code INTEGER,
    summary VARCHAR(500),
    resource_usage_json TEXT NOT NULL DEFAULT '{}',
    lease_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    cancellation_requested_at TIMESTAMP WITH TIME ZONE,
    termination_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    recovered_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_stage_attempts_stage_num UNIQUE (stage_run_id, attempt_number),
    CONSTRAINT uq_stage_attempts_stage_fence UNIQUE (stage_run_id, fencing_generation),
    CONSTRAINT uq_stage_attempts_lease_token UNIQUE (lease_token),
    CONSTRAINT ck_stage_attempts_status CHECK (
        status IN (
            'CLAIMED',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'TIMED_OUT',
            'CANCELLED',
            'INTERRUPTED',
            'EXPIRED'
        )
    ),
    CONSTRAINT ck_stage_attempts_failure_category CHECK (
        failure_category IN (
            'NONE',
            'INFRASTRUCTURE_FAILURE',
            'WORKER_CRASH',
            'LEASE_EXPIRED',
            'EXECUTION_TIMEOUT',
            'OPERATION_FAILURE',
            'CANCELLED',
            'UNSUPPORTED_OPERATION'
        )
    ),
    CONSTRAINT ck_stage_attempts_num_positive CHECK (attempt_number >= 1),
    CONSTRAINT ck_stage_attempts_fence_positive CHECK (fencing_generation >= 1),
    CONSTRAINT fk_stage_attempts_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_attempts_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_attempts_stage FOREIGN KEY (stage_run_id) REFERENCES pipeline_stage_runs (id) ON DELETE CASCADE
);

CREATE INDEX idx_stage_attempts_stage_order ON stage_execution_attempts (stage_run_id, attempt_number ASC);
CREATE INDEX idx_stage_attempts_status_lease ON stage_execution_attempts (status, lease_expires_at);
CREATE INDEX idx_stage_attempts_worker_status ON stage_execution_attempts (worker_id, status);
CREATE INDEX idx_stage_attempts_workspace_run ON stage_execution_attempts (workspace_id, pipeline_run_id);

CREATE TABLE stage_execution_logs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    stage_run_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    worker_id VARCHAR(128) NOT NULL,
    sequence_number INTEGER NOT NULL,
    stream_type VARCHAR(16) NOT NULL,
    message VARCHAR(2048) NOT NULL,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_stage_logs_attempt_seq UNIQUE (attempt_id, sequence_number),
    CONSTRAINT ck_stage_logs_stream CHECK (stream_type IN ('STDOUT', 'STDERR', 'SYSTEM')),
    CONSTRAINT ck_stage_logs_seq_positive CHECK (sequence_number >= 1),
    CONSTRAINT ck_stage_logs_attempt_positive CHECK (attempt_number >= 1),
    CONSTRAINT fk_stage_logs_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_logs_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_logs_stage FOREIGN KEY (stage_run_id) REFERENCES pipeline_stage_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_logs_attempt FOREIGN KEY (attempt_id) REFERENCES stage_execution_attempts (id) ON DELETE CASCADE
);

CREATE INDEX idx_stage_logs_stage_attempt_seq ON stage_execution_logs (stage_run_id, attempt_number ASC, sequence_number ASC);
CREATE INDEX idx_stage_logs_workspace_run ON stage_execution_logs (workspace_id, pipeline_run_id);
