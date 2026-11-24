# CloudForge — Phase 3.3: Isolated Build Workers, Secure Execution & Pipeline Scheduling

> **Status:** Implemented for trusted local/development execution of strictly allowlisted operations. Arbitrary untrusted repository builds (requiring microVM isolation), encrypted secret storage (Phase 3.4), OCI container registry publishing (Phase 3.5), Kubernetes deployments (Phase 3.6), and release rollback (Phase 3.7) remain **Planned**.

---

## 1. Security Domain Separation

CloudForge Phase 3.3 separates execution into two distinct security domains:

| Domain | Location | Responsibilities & Boundaries |
| --- | --- | --- |
| **Control Plane** | `backend/` (`com.cloudforge.backend.worker`) | User OAuth/session auth, workspace RBAC, PostgreSQL persistence, worker credential verification, bounded FIFO scheduling, atomic fenced leasing, heartbeat tracking, cancellation signaling, expired lease recovery, and sanitized bounded log storage. **Never executes Docker commands or mounts `/var/run/docker.sock`.** |
| **Execution Plane** | `worker/` (`cloudforge-worker.jar`) | Standalone Java 17 host-side process authenticating strictly to `/api/internal/workers/**`, claiming leased stage descriptors, running fixed compile-time allowlisted operations inside hardened ephemeral Docker containers, streaming bounded logs, enforcing timeouts/cancellation, and reporting verified outcomes. |

### Deployment Safety (Render Compatibility)

- Both `cloudforge.workers.enabled` and `cloudforge.workers.scheduler-enabled` default to **`false`** in `application.yml` and are explicitly locked to **`false`** in `application-render.yml`.
- When disabled or when no worker credentials are provisioned, `/api/internal/workers/**` **fails closed** (`503 Service Unavailable`).
- No Docker-in-Docker or privileged containers are added to `compose.yaml` or `Dockerfile.render`.

---

## 2. Database Migration (`V5__isolated_worker_execution.sql`)

Non-destructive Flyway migration preserving `V1`–`V4` untouched:

- **Extensions to `pipeline_runs`:** `cancellation_requested_at`, `cancellation_reason`.
- **Extensions to `pipeline_stage_runs`:** `metadata_json`, `allowed_operation`, `active_lease_token`, `fencing_generation`, `next_retry_at`, `cancellation_requested_at`, plus `idx_pipeline_stage_runs_ready_schedule`.
- **New table `worker_nodes`:** Trusted worker registry (`worker_id`, `display_name`, `adapter_type`, `status`, `supported_operations_json`, `max_concurrency`, `active_assignments`, `credential_key_id`, `host_label`, `agent_version`, `registered_at`, `last_heartbeat_at`, `offline_at`, `row_version`).
- **New table `worker_credentials`:** Per-worker credential lifecycle metadata storing **only** hex SHA-256 `token_hash` (never plaintext), `key_id`, `status` (`ACTIVE`/`REVOKED`), `expires_at`, `revoked_at`.
- **New table `stage_execution_attempts`:** Persistent lease, attempt history, and fencing records (`UNIQUE (stage_run_id, attempt_number)`, `UNIQUE (stage_run_id, fencing_generation)`, `UNIQUE (lease_token)`), tracking `status` (`CLAIMED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `CANCELLED`, `INTERRUPTED`, `EXPIRED`), `failure_category`, `exit_code`, `resource_usage_json`, `lease_started_at`, `lease_expires_at`, `last_heartbeat_at`, `cancellation_requested_at`, `termination_confirmed`, `recovered_at`, `row_version`.
- **New table `stage_execution_logs`:** Bounded, ordered, sanitized logs (`UNIQUE (attempt_id, sequence_number)`, `stream_type` in `STDOUT`/`STDERR`/`SYSTEM`, `message` <= 2048 chars, `truncated`, `occurred_at`).

---

## 3. Worker Authentication, Provisioning, Rotation & Revocation

- Dedicated `@Order(1)` stateless `SecurityFilterChain` in `SecurityConfig` matches `/api/internal/workers/**`, disables browser CORS and CSRF cookies, excludes OAuth/session cookies, and requires `ROLE_TRUSTED_WORKER` via `WorkerAuthenticationFilter`.
- Workers present `X-CloudForge-Worker-Id` and `Authorization: Bearer <per-worker-token>`.
- `WorkerCredentialService` compares SHA-256 hashes in constant time (`MessageDigest.isEqual`), verifies `workerId` binding (preventing spoofing across workers with `403 Forbidden`), rejects expired credentials (`401`), and honors immediate revocation (`worker_credentials.status = REVOKED`).
- **Provisioning:** Supplied via environment variable `CLOUDFORGE_WORKER_CREDENTIALS` (`workerId:keyId:secret[:expiresAtIso],...`, secret >= 16 non-placeholder chars) or hashed records in `worker_credentials`. Never committed to Git or exposed to browser JS.
- **Rotation:** Provision a second key (`key-2`) for the same `workerId`, roll the worker agent to `key-2`, then revoke `key-1` via `worker_credentials` without downtime.

---

## 4. Integration with `WorkerAssignmentContract`, Scheduler, Leasing & Fencing

- Preserves Phase 3.2 `WorkerAssignmentContract` (`listReadyStageTickets`, `StageDispatchDescriptor`, `WorkerExecutionOutcome`) and extends it with `LeasedStageWork` and `AuthenticatedAttemptResult`.
- **Bounded FIFO Scheduler (`WorkerSchedulerService`):** Discovers eligible `READY` stages using a scalar projection (`findEligibleReadyStages`) bounded by `maxClaimBatchSize` (preventing unbounded scans and avoiding Hibernate L1 cache hydration prior to locking).
- **Atomic Claim & Deterministic Lock Ordering:** Acquires `PESSIMISTIC_WRITE` row locks strictly in order `Worker -> Run -> Stage` (preventing deadlocks and guaranteeing at most 1 worker ever claims a stage attempt). Increments `fencing_generation`, issues a 24-byte CSPRNG `lease_token`, commits immediately before returning `LeasedStageWork` (no DB transactions held during container execution).
- **Fencing & Result Validation (`WorkerResultProcessorService`):** Completion requests must match authenticated `workerId`, active `leaseToken`, current `fencingGeneration`, `pipelineRunId`, `stageRunId`, `attemptNumber`, unexpired lease, and non-terminal stage/run state. Stale, expired, duplicate, or mismatched reports are rejected (`409 Conflict` / `403 Forbidden` / `400 Bad Request`).

---

## 5. State Machine, Retries, Cancellation & Recovery

- **Terminal `FAILED` Invariant Preserved:** Individual failed attempts are recorded in `stage_execution_attempts`. For retryable categories (`INFRASTRUCTURE_FAILURE`, `WORKER_CRASH`, `LEASE_EXPIRED`, `EXECUTION_TIMEOUT`) on idempotent operations within `maxRetries`, the stage transitions `RUNNING -> READY` via `requeueForRetry(...)` with exponential backoff (`next_retry_at`), never transitioning terminal `FAILED` back to `RUNNING`. Terminal `FAILED` is entered only when retries are exhausted or the failure is non-retryable (`OPERATION_FAILURE`), triggering transitive `SKIPPED` downstream propagation and terminal run `FAILED`.
- **Cancellation Races:**
  - `QUEUED` runs cancel immediately (preserving Phase 3.2 behavior).
  - `RUNNING` runs with active worker-leased stages immediately cancel unstarted (`PENDING`/`READY`) stages, persist `cancellation_requested_at` on the run, active stages, and active attempts, and signal `cancelRequested: true` on the worker's next heartbeat.
  - Workers must stop/kill the container, verify via `docker inspect` that the container is gone, and report `terminationConfirmed = true` (`terminationConfirmed = false` is rejected with `400`).
- **Lease Recovery (`WorkerRecoveryService`):** Bounded recovery sweeps mark workers with expired heartbeats `OFFLINE`, fence expired active attempts (`EXPIRED`, increment `fencing_generation`, clear `active_lease_token`), decrement worker concurrency, and re-queue strictly idempotent attempts with remaining retry budget (never blindly redispatching non-idempotent work).

---

## 6. Hardened Local Docker Execution Adapter & Allowlisted Operations

`DockerIsolatedExecutionAdapter` invokes `docker run` via `ProcessBuilder` argument arrays (never a host shell) with strict container hardening:

- `--rm` + `--init` + deterministic name `cf-exec-<attemptPrefix>` + `cloudforge.managed=true` label
- `--user 65534:65534` (non-root)
- `--cap-drop=ALL` & `--security-opt no-new-privileges:true`
- `--read-only` root filesystem + `--tmpfs /tmp:rw,noexec,nosuid,size=64m`
- `--network=none` (zero network egress)
- `--cpus 0.5`, `--memory 128m`, `--memory-swap 128m`, `--pids-limit 64`
- Trusted allowlisted executor image only (`alpine:3.20`, `busybox:1.36`, or pinned `@sha256:...` digest)
- Zero `--privileged`, zero Docker socket mount, zero host mounts, zero secrets in container env

### Allowlisted Phase 3.3 Operations

| Operation | Compatible Stage Types | Idempotent | Behavior |
| --- | --- | --- | --- |
| `ENV_VALIDATION` | `SOURCE_PREP` | Yes | Verifies non-root UID 65534, read-only rootfs, and bounded `/tmp`. |
| `CONTROLLED_SMOKE_TEST` | `TEST` | Yes | Deterministic UTF-8 stdout/stderr and SHA-256 smoke verification. |
| `WORKER_DIAGNOSTICS` | `SOURCE_PREP`, `TEST` | Yes | Checks kernel/arch, cgroup memory/PID limits, and verifies no `docker.sock` inside container. |

Unsupported stage types (`BUILD`, `SECURITY_SCAN`, `CONTAINER_PACKAGE`, `DEPLOYMENT`) and declarative stages without an allowlisted operation are never dispatched to a generic shell and never falsely marked succeeded; the API and UI display explicit capability boundary messages.

---

## 7. REST Endpoints

### Internal Worker Endpoints (`/api/internal/workers/**` — `ROLE_TRUSTED_WORKER` only)

- `POST /api/internal/workers/register`
- `POST /api/internal/workers/{workerId}/heartbeat`
- `POST /api/internal/workers/{workerId}/claim` (`200 OK` with `LeasedStageWork` or `204 No Content`)
- `POST /api/internal/workers/{workerId}/attempts/{attemptId}/running`
- `POST /api/internal/workers/{workerId}/attempts/{attemptId}/logs`
- `POST /api/internal/workers/{workerId}/attempts/{attemptId}/complete`
- `POST /api/internal/workers/{workerId}/status`

### Workspace Read-Only Execution Endpoints (`/api/workspaces/{workspaceId}/pipelines/...`)

- `GET /workers/status` (`OWNER`, `DEVELOPER`, `VIEWER`; detailed global worker list restricted to `OWNER`)
- `GET /runs/{runId}/stages/{stageRunId}/attempts` (`OWNER`, `DEVELOPER`, `VIEWER`)
- `GET /runs/{runId}/stages/{stageRunId}/logs` (`OWNER`, `DEVELOPER`, `VIEWER`; paginated, redacted)

---

## 8. Implementation Status Matrix (Implemented / Tested / Untested / Planned)

| Capability | Status | Verification Evidence |
| --- | --- | --- |
| `V5` Flyway migration & 21-table schema | Implemented & Tested | Verified on H2 (`FlywayMigrationTest`) and PostgreSQL 17 CI (`PostgresSearchQueryRegressionTest`). |
| Worker auth, spoofing, expiration, revocation, user exclusion, Render fail-closed | Implemented & Tested | `WorkerSecurityAndExecutionEndToEndTest`, `RenderWorkerSafetyTest`. |
| Multi-worker atomic claim concurrency | Implemented & Tested | 4-thread race verified in `WorkerConcurrencyTest` (strictly 1 winner). |
| Multi-stage E2E lifecycle, retries, timeouts, active cancellation, recovery fencing, log bounds & redaction | Implemented & Tested | `WorkerSecurityAndExecutionEndToEndTest`, `LogSanitizerAndOperationUnitTest`, `WorkerAgentLoopUnitTest`. |
| Hardened Docker container execution, timeout kill, cancel kill, cleanup | Implemented & CI-Tested | `DockerIsolationSecurityUnitTest` (local + CI) and `DockerIsolatedExecutionIntegrationTest` (live Docker Engine in GitHub Actions `ubuntu-latest` CI; skipped locally when Docker Desktop daemon is stopped). |
| Frontend worker pool card, allowlisted op editor, attempts & monospace sanitized logs | Implemented & Tested | Vitest (`24/24` passing including `CloudForgePipelines.test.tsx`), `tsc -b --noEmit`, `vite build`. |
| Multi-tenant hostile repository builds (microVM/gVisor/Kata/Firecracker) | **Planned** | Explicitly deferred beyond Phase 3.3 — Docker's default kernel-sharing boundary is insufficient for hostile untrusted user code. |
| Encrypted secrets vault / KMS (Phase 3.4), OCI registry (Phase 3.5), Kubernetes rollouts & rollback (Phases 3.6–3.7) | **Planned** | Deferred to Phases 3.4–3.7. |
