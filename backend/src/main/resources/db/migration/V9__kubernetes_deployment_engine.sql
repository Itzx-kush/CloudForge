-- ============================================================================
-- CloudForge Phase 3.6 — V9: Secure Kubernetes Deployment Engine, Cluster
--                            Connections, Namespace Isolation & Rolling Updates
-- ============================================================================
-- Non-destructive migration preserving V1-V8 completely untouched.
-- Adds KUBERNETES_DEPLOY worker adapter capability, Kubernetes failure
-- categories, workspace-scoped Kubernetes cluster connections (referencing
-- Phase 3.4 AES-256-GCM encrypted secrets only — never plaintext bearer tokens
-- or kubeconfigs), operator-approved namespace bindings, declarative workload
-- deployment configurations, immutable deployment operations ledger (preparing
-- Phase 3.7 release history & rollback), and chronological deployment events.

ALTER TABLE worker_nodes DROP CONSTRAINT ck_worker_nodes_adapter;
ALTER TABLE worker_nodes ADD CONSTRAINT ck_worker_nodes_adapter
    CHECK (adapter_type IN ('DOCKER_ISOLATED', 'GITHUB_SOURCE', 'OCI_BUILDKIT', 'KUBERNETES_DEPLOY', 'MOCK_TEST'));

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
            'DIGEST_VERIFICATION_FAILED',
            'CLUSTER_UNAVAILABLE',
            'CLUSTER_AUTH_FAILED',
            'CLUSTER_TLS_ERROR',
            'K8S_RBAC_DENIED',
            'K8S_NAMESPACE_INVALID',
            'K8S_MANIFEST_REJECTED',
            'K8S_QUOTA_EXCEEDED',
            'K8S_IMAGE_PULL_FAILED',
            'K8S_CRASH_LOOP',
            'K8S_UNSCHEDULABLE',
            'K8S_READINESS_FAILED',
            'K8S_ROLLOUT_TIMEOUT',
            'K8S_RECONCILIATION_REQUIRED'
        )
    );

CREATE TABLE kubernetes_cluster_connections (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    cluster_identifier VARCHAR(128) NOT NULL,
    cluster_type VARCHAR(32) NOT NULL,
    environment_category VARCHAR(32) NOT NULL,
    api_server_url VARCHAR(512) NOT NULL,
    ca_certificate_pem TEXT NOT NULL,
    ca_certificate_fingerprint_sha256 VARCHAR(64) NOT NULL,
    credential_project_id UUID NOT NULL,
    credential_environment_id UUID NOT NULL,
    bearer_token_secret_id UUID NOT NULL,
    service_account_name VARCHAR(128) NOT NULL,
    allow_local_loopback BOOLEAN NOT NULL DEFAULT FALSE,
    production_approval_required BOOLEAN NOT NULL DEFAULT TRUE,
    connection_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_VALIDATION',
    validation_nonce BIGINT NOT NULL DEFAULT 0,
    validation_requested_at TIMESTAMP WITH TIME ZONE,
    last_validated_at TIMESTAMP WITH TIME ZONE,
    last_validation_worker_id VARCHAR(128),
    last_validation_error VARCHAR(500),
    kubernetes_version VARCHAR(64),
    supported_capabilities_json TEXT NOT NULL DEFAULT '{}',
    description VARCHAR(500),
    created_by_user_id UUID,
    updated_by_user_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_k8s_cluster_ws_slug UNIQUE (workspace_id, slug),
    CONSTRAINT uq_k8s_cluster_ws_identifier UNIQUE (workspace_id, cluster_identifier),
    CONSTRAINT ck_k8s_cluster_type CHECK (cluster_type IN ('KIND_LOCAL', 'K3D_LOCAL', 'K3S_LOCAL', 'REMOTE_KUBERNETES')),
    CONSTRAINT ck_k8s_cluster_env_cat CHECK (environment_category IN ('DEVELOPMENT', 'STAGING', 'PRODUCTION')),
    CONSTRAINT ck_k8s_cluster_status CHECK (
        connection_status IN ('PENDING_VALIDATION', 'VALIDATING', 'VALIDATED', 'VALIDATION_FAILED', 'DISABLED')
    ),
    CONSTRAINT fk_k8s_cluster_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_cluster_cred_proj FOREIGN KEY (credential_project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_k8s_cluster_cred_env FOREIGN KEY (credential_environment_id) REFERENCES project_environments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_k8s_cluster_token_sec FOREIGN KEY (bearer_token_secret_id) REFERENCES environment_secrets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_k8s_cluster_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_k8s_cluster_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_k8s_cluster_ws_status ON kubernetes_cluster_connections (workspace_id, connection_status);
CREATE INDEX idx_k8s_cluster_status_val_req ON kubernetes_cluster_connections (connection_status, validation_requested_at);

CREATE TABLE kubernetes_namespace_bindings (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    cluster_connection_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    namespace_name VARCHAR(63) NOT NULL,
    workload_service_account_name VARCHAR(128) NOT NULL DEFAULT 'cloudforge-workload',
    image_pull_secret_name VARCHAR(128),
    pod_security_standard VARCHAR(32) NOT NULL DEFAULT 'RESTRICTED',
    network_policy_mode VARCHAR(32) NOT NULL DEFAULT 'DEFAULT_DENY',
    resource_quota_configured BOOLEAN NOT NULL DEFAULT TRUE,
    limit_range_configured BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    verified_at TIMESTAMP WITH TIME ZONE,
    created_by_user_id UUID,
    updated_by_user_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_k8s_ns_cluster_name UNIQUE (cluster_connection_id, namespace_name),
    CONSTRAINT uq_k8s_ns_ws_proj_env_cluster UNIQUE (workspace_id, project_id, environment_id, cluster_connection_id),
    CONSTRAINT ck_k8s_ns_pss CHECK (pod_security_standard IN ('RESTRICTED', 'BASELINE')),
    CONSTRAINT ck_k8s_ns_netpol CHECK (network_policy_mode IN ('DEFAULT_DENY', 'ENFORCED_VERIFIED', 'UNENFORCED_LOCAL_CNI')),
    CONSTRAINT ck_k8s_ns_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_k8s_ns_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_ns_cluster FOREIGN KEY (cluster_connection_id) REFERENCES kubernetes_cluster_connections (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_ns_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_ns_environment FOREIGN KEY (environment_id) REFERENCES project_environments (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_ns_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_k8s_ns_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_k8s_ns_ws_cluster ON kubernetes_namespace_bindings (workspace_id, cluster_connection_id, status);
CREATE INDEX idx_k8s_ns_ws_proj_env ON kubernetes_namespace_bindings (workspace_id, project_id, environment_id);

CREATE TABLE kubernetes_deployment_configs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    cluster_connection_id UUID NOT NULL,
    namespace_binding_id UUID NOT NULL,
    namespace_name VARCHAR(63) NOT NULL,
    deployment_name VARCHAR(63) NOT NULL,
    service_name VARCHAR(63) NOT NULL,
    replica_count INTEGER NOT NULL DEFAULT 1,
    container_port INTEGER NOT NULL DEFAULT 8080,
    service_port INTEGER NOT NULL DEFAULT 80,
    cpu_request VARCHAR(32) NOT NULL DEFAULT '100m',
    cpu_limit VARCHAR(32) NOT NULL DEFAULT '500m',
    memory_request VARCHAR(32) NOT NULL DEFAULT '128Mi',
    memory_limit VARCHAR(32) NOT NULL DEFAULT '512Mi',
    readiness_probe_json TEXT NOT NULL,
    liveness_probe_json TEXT NOT NULL,
    startup_probe_json TEXT,
    rolling_max_surge VARCHAR(16) NOT NULL DEFAULT '1',
    rolling_max_unavailable VARCHAR(16) NOT NULL DEFAULT '0',
    progress_deadline_seconds INTEGER NOT NULL DEFAULT 300,
    deployment_timeout_seconds INTEGER NOT NULL DEFAULT 300,
    read_only_root_filesystem BOOLEAN NOT NULL DEFAULT TRUE,
    run_as_non_root BOOLEAN NOT NULL DEFAULT TRUE,
    run_as_user BIGINT NOT NULL DEFAULT 10001,
    config_checksum_sha256 VARCHAR(64) NOT NULL,
    created_by_user_id UUID,
    updated_by_user_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_k8s_cfg_target_name UNIQUE (workspace_id, project_id, environment_id, cluster_connection_id, deployment_name),
    CONSTRAINT ck_k8s_cfg_replicas CHECK (replica_count >= 1 AND replica_count <= 20),
    CONSTRAINT ck_k8s_cfg_ports CHECK (container_port >= 1 AND container_port <= 65535 AND service_port >= 1 AND service_port <= 65535),
    CONSTRAINT ck_k8s_cfg_timeouts CHECK (progress_deadline_seconds >= 30 AND progress_deadline_seconds <= 1800 AND deployment_timeout_seconds >= 30 AND deployment_timeout_seconds <= 1800),
    CONSTRAINT ck_k8s_cfg_run_as_user CHECK (run_as_user >= 1000 AND run_as_user <= 65534),
    CONSTRAINT fk_k8s_cfg_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_cfg_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_cfg_environment FOREIGN KEY (environment_id) REFERENCES project_environments (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_cfg_cluster FOREIGN KEY (cluster_connection_id) REFERENCES kubernetes_cluster_connections (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_cfg_ns_binding FOREIGN KEY (namespace_binding_id) REFERENCES kubernetes_namespace_bindings (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_cfg_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_k8s_cfg_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_k8s_cfg_ws_proj_env ON kubernetes_deployment_configs (workspace_id, project_id, environment_id);

CREATE TABLE kubernetes_deployment_operations (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    cluster_connection_id UUID NOT NULL,
    namespace_binding_id UUID NOT NULL,
    deployment_config_id UUID,
    oci_artifact_id UUID NOT NULL,
    pipeline_run_id UUID NOT NULL,
    stage_run_id UUID NOT NULL,
    attempt_id UUID,
    idempotency_key VARCHAR(128),
    request_fingerprint_sha256 VARCHAR(64),
    namespace_name VARCHAR(63) NOT NULL,
    deployment_name VARCHAR(63) NOT NULL,
    service_name VARCHAR(63) NOT NULL,
    immutable_image_reference VARCHAR(512) NOT NULL,
    manifest_digest VARCHAR(128) NOT NULL,
    source_commit_sha VARCHAR(64) NOT NULL,
    manifest_checksum_sha256 VARCHAR(64) NOT NULL,
    config_snapshot_json TEXT NOT NULL,
    requested_replicas INTEGER NOT NULL,
    observed_replicas INTEGER NOT NULL DEFAULT 0,
    updated_replicas INTEGER NOT NULL DEFAULT 0,
    ready_replicas INTEGER NOT NULL DEFAULT 0,
    available_replicas INTEGER NOT NULL DEFAULT 0,
    observed_generation BIGINT,
    k8s_deployment_uid VARCHAR(128),
    k8s_service_uid VARCHAR(128),
    rollout_conditions_json TEXT NOT NULL DEFAULT '[]',
    pod_diagnostics_json TEXT NOT NULL DEFAULT '[]',
    status VARCHAR(32) NOT NULL,
    apply_initiated BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_worker_id VARCHAR(128),
    fencing_generation BIGINT NOT NULL DEFAULT 0,
    production_approval_token VARCHAR(128),
    production_approved_by_user_id UUID,
    production_approved_at TIMESTAMP WITH TIME ZONE,
    requested_by_user_id UUID,
    cancelled_by_user_id UUID,
    cancellation_requested_at TIMESTAMP WITH TIME ZONE,
    failure_category VARCHAR(40),
    error_summary VARCHAR(500),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_k8s_dep_ops_stage_run UNIQUE (stage_run_id),
    CONSTRAINT uq_k8s_dep_ops_ws_idem UNIQUE (workspace_id, idempotency_key),
    CONSTRAINT ck_k8s_dep_ops_status CHECK (
        status IN (
            'PENDING',
            'VALIDATING',
            'APPLYING',
            'ROLLING_OUT',
            'VERIFYING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'RECONCILIATION_REQUIRED'
        )
    ),
    CONSTRAINT ck_k8s_dep_ops_replicas CHECK (
        requested_replicas >= 1 AND requested_replicas <= 20
        AND observed_replicas >= 0 AND updated_replicas >= 0 AND ready_replicas >= 0 AND available_replicas >= 0
    ),
    CONSTRAINT fk_k8s_dep_ops_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_dep_ops_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_dep_ops_environment FOREIGN KEY (environment_id) REFERENCES project_environments (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_dep_ops_cluster FOREIGN KEY (cluster_connection_id) REFERENCES kubernetes_cluster_connections (id) ON DELETE RESTRICT,
    CONSTRAINT fk_k8s_dep_ops_ns_binding FOREIGN KEY (namespace_binding_id) REFERENCES kubernetes_namespace_bindings (id) ON DELETE RESTRICT,
    CONSTRAINT fk_k8s_dep_ops_config FOREIGN KEY (deployment_config_id) REFERENCES kubernetes_deployment_configs (id) ON DELETE SET NULL,
    CONSTRAINT fk_k8s_dep_ops_artifact FOREIGN KEY (oci_artifact_id) REFERENCES oci_image_artifacts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_k8s_dep_ops_run FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_dep_ops_stage FOREIGN KEY (stage_run_id) REFERENCES pipeline_stage_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_dep_ops_attempt FOREIGN KEY (attempt_id) REFERENCES stage_execution_attempts (id) ON DELETE SET NULL,
    CONSTRAINT fk_k8s_dep_ops_prod_approver FOREIGN KEY (production_approved_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_k8s_dep_ops_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_k8s_dep_ops_cancelled_by FOREIGN KEY (cancelled_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_k8s_dep_ops_ws_created ON kubernetes_deployment_operations (workspace_id, created_at DESC);
CREATE INDEX idx_k8s_dep_ops_ws_status ON kubernetes_deployment_operations (workspace_id, status, created_at DESC);
CREATE INDEX idx_k8s_dep_ops_proj_env ON kubernetes_deployment_operations (workspace_id, project_id, environment_id, created_at DESC);
CREATE INDEX idx_k8s_dep_ops_cluster_ns ON kubernetes_deployment_operations (cluster_connection_id, namespace_name, deployment_name, status);
CREATE INDEX idx_k8s_dep_ops_run ON kubernetes_deployment_operations (pipeline_run_id);

CREATE TABLE kubernetes_deployment_events (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    deployment_operation_id UUID NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    actor_label VARCHAR(128) NOT NULL,
    message VARCHAR(500) NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_k8s_dep_events_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_k8s_dep_events_op FOREIGN KEY (deployment_operation_id) REFERENCES kubernetes_deployment_operations (id) ON DELETE CASCADE
);

CREATE INDEX idx_k8s_dep_events_op_order ON kubernetes_deployment_events (deployment_operation_id, occurred_at ASC, created_at ASC);
CREATE INDEX idx_k8s_dep_events_ws_occurred ON kubernetes_deployment_events (workspace_id, occurred_at DESC);
