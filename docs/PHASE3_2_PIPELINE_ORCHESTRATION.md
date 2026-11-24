# CloudForge — Phase 3.2: Production-Grade Pipeline Orchestration Engine

> **Status:** Implemented (Phase 3.2). Phases 3.3–3.7 (isolated build workers, encrypted secrets vault, OCI container registry publishing, Kubernetes deployments, and release rollback) remain **Planned**.

---

## 1. Overview & Strict Scope Boundaries

Phase 3.2 introduces CloudForge's persistent **Pipeline Orchestration Engine** (`com.cloudforge.backend.orchestration`) while preserving every Phase 1, Phase 2, PR #6 premium frontend redesign, and Phase 3.1 read-only GitHub Actions capability.

CloudForge now clearly distinguishes two separate domains inside the **Pipelines** console:

1. **GitHub Actions (Phase 3.1 — Read-Only):** External workflows, historical runs, and live job/step telemetry synchronized from GitHub App installations (`actions: read`).
2. **CloudForge Pipelines (Phase 3.2 — Orchestration Engine):** Workspace- and project-scoped declarative pipeline definitions, DAG stage graphs, immutable execution snapshots, restart-safe idempotent run creation, deterministic stage readiness evaluation, chronological transition event history, and race-safe cancellation.

### Intentional Security & Scope Boundaries

- **Zero Arbitrary Execution:** Pipeline definitions do not accept arbitrary shell commands, repository scripts, container build instructions, or deployment credentials.
- **No Fake Execution:** Queueing a CloudForge pipeline run persists an immutable definition snapshot, promotes root stages (stages with zero predecessors) from `PENDING` to `READY`, and keeps the run in `QUEUED` with explicit status reason **`Queued — Awaiting execution infrastructure`**.
- **No User-Accessible Internal Execution Endpoints:** No REST endpoint allows a user to mark a pipeline run or stage `RUNNING`, `SUCCEEDED`, or `FAILED`. Those transitions are reserved exclusively for future Phase 3.3 isolated build workers through `WorkerAssignmentContract`.

---

## 2. Domain Model & Backend Architecture

All orchestration components are isolated in modular subpackages under `com.cloudforge.backend.orchestration`:

| Package | Components | Responsibility |
| --- | --- | --- |
| `orchestration.entity` | `PipelineDefinitionEntity`, `PipelineStageDefinitionEntity`, `PipelineRunEntity`, `PipelineStageRunEntity`, `PipelineRunEventEntity`, `PipelineIdempotencyKeyEntity`, `PipelineStageType`, `PipelineRunStatus`, `PipelineStageStatus`, `PipelineTriggerType` | PostgreSQL JPA entities with optimistic locking (`@Version` `row_version`) and encapsulated state transitions (no public `setStatus` setters). |
| `orchestration.engine` | `PipelineStateMachine`, `StageStateMachine`, `StageDagValidator`, `StageDependencyEvaluator`, `WorkerAssignmentContract` | Pure deterministic state machines, Kahn topological sort & security validation, DAG progression/failure propagation, and Phase 3.3 worker integration contract. |
| `orchestration.repository` | `PipelineDefinitionRepository`, `PipelineStageDefinitionRepository`, `PipelineRunRepository`, `PipelineStageRunRepository`, `PipelineRunEventRepository`, `PipelineIdempotencyKeyRepository` | Workspace-scoped JPA queries, typed Criteria search (PostgreSQL-safe, zero `lower(bytea)` issues), pessimistic `PESSIMISTIC_WRITE` row locks, and atomic `INSERT ... ON CONFLICT DO NOTHING` idempotency claims. |
| `orchestration.service` | `PipelineDefinitionService`, `PipelineIdempotencyService`, `PipelineOrchestrationService` | Workspace RBAC enforcement, DAG validation, optimistic/pessimistic concurrency control, immutable snapshot creation, run/stage lifecycle orchestration, cancellation, and audit logging. |
| `orchestration.controller` | `PipelineOrchestrationController` | 9 authenticated, workspace-scoped REST endpoints under `/api/workspaces/{workspaceId}/pipelines`. |
| `orchestration.dto` | Request & response Java 17 records | Bean Validation constraints, structured field errors, and non-sensitive API payloads. |

---

## 3. PostgreSQL Database Migration (`V4__pipeline_orchestration_engine.sql`)

Non-destructive Flyway migration `backend/src/main/resources/db/migration/V4__pipeline_orchestration_engine.sql` preserves all existing Phase 1–3.1 tables (`V1`, `V2`, `V3` untouched) and creates six tables:

| Table | Purpose | Key Constraints & Indexes |
| --- | --- | --- |
| `pipeline_definitions` | Workspace- and project-scoped declarative pipeline definitions (`name`, `slug`, `description`, `enabled`, `definition_version`, `row_version`, optional `linked_repository_id`). | `UNIQUE (project_id, slug)`, `CHECK (definition_version >= 1)`, workspace & project indexes. |
| `pipeline_stage_definitions` | Ordered stage definitions belonging to a `pipeline_definition` (`stage_key`, `display_name`, `stage_type`, `execution_order`, `depends_on_json`, `timeout_seconds`, `max_retries`, `allow_failure`, `metadata_json`). | `UNIQUE (pipeline_definition_id, stage_key)`, `CHECK` on `stage_type`, `timeout_seconds` (`30..7200`), `max_retries` (`0..5`). |
| `pipeline_runs` | Persistent execution intents (`run_number`, `definition_name`, `definition_version`, `definition_snapshot_json`, `trigger_type`, `target_branch`, `commit_sha`, `status`, `status_reason`, lifecycle timestamps, `row_version`). | `UNIQUE (pipeline_definition_id, run_number)`, `CHECK` on `status` & `trigger_type`, workspace/project/status indexes. |
| `pipeline_stage_runs` | Immutable per-run stage instances initialized from the validated definition snapshot (`status`, `attempt_number`, `worker_id`, `lease_expires_at`, `ready_at`, `started_at`, `completed_at`, `row_version`). | `UNIQUE (pipeline_run_id, stage_key)`, `CHECK` on `status`, `stage_type`, `attempt_number >= 1`, ready-stage scheduling index. |
| `pipeline_run_events` | Immutable chronological transition log (`event_type`, `from_status`, `to_status`, `actor_user_id`, `actor_username`, `message`, `metadata_json`, `occurred_at`). | Foreign keys to `workspaces`, `pipeline_runs`, optional `pipeline_stage_runs`, ordered index on `(pipeline_run_id, occurred_at)`. |
| `pipeline_idempotency_keys` | Restart-safe database-backed idempotency ledger (`workspace_id`, `actor_user_id`, `operation`, `idempotency_key`, `request_fingerprint`, `pipeline_run_id`, `expires_at`). | `UNIQUE (workspace_id, actor_user_id, operation, idempotency_key)`, expiration index. |

---

## 4. Pipeline & Stage State Machines

Direct status mutation is blocked at the entity level (`PipelineRunEntity.transitionStatus(...)` and `PipelineStageRunEntity.transitionStatus(...)` delegate unconditionally to `PipelineStateMachine` and `StageStateMachine`). Illegal transitions and transitions out of terminal states throw `ConflictException` (`409 Conflict`).

### 4.1 Pipeline Run State Machine

| From State | Allowed Next States | Terminal? |
| --- | --- | --- |
| `QUEUED` | `RUNNING`, `CANCELLED`, `FAILED` | No |
| `RUNNING` | `SUCCEEDED`, `FAILED`, `CANCELLED` | No |
| `SUCCEEDED` | *(none)* | Yes |
| `FAILED` | *(none)* | Yes |
| `CANCELLED` | *(none)* | Yes |

### 4.2 Stage Run State Machine

| From State | Allowed Next States | Terminal? |
| --- | --- | --- |
| `PENDING` | `READY`, `SKIPPED`, `CANCELLED` | No |
| `READY` | `RUNNING`, `SKIPPED`, `CANCELLED` | No |
| `RUNNING` | `SUCCEEDED`, `FAILED`, `CANCELLED` | No |
| `SUCCEEDED` | *(none)* | Yes |
| `FAILED` | *(none)* | Yes |
| `SKIPPED` | *(none)* | Yes |
| `CANCELLED` | *(none)* | Yes |

---

## 5. Declarative Stage Types, DAG Validation & Dependency Evaluation

### Allowed Stage Types

`SOURCE_PREP`, `BUILD`, `TEST`, `SECURITY_SCAN`, `CONTAINER_PACKAGE`, `DEPLOYMENT`.

### `StageDagValidator` Guardrails

- **Bounds:** `1..25` stages per pipeline definition, `0..10` dependencies per stage, `timeoutSeconds` in `30..7200`, `maxRetries` in `0..5`, max `16` metadata entries (`<= 256` chars/value).
- **Graph Integrity:** Enforces unique stage keys (`^[a-z0-9][a-z0-9_-]{0,63}$`), rejects self-dependencies, rejects missing predecessor references, and executes Kahn's topological sort to detect and reject cycles with deterministic error reporting.
- **No Shell Execution or Secrets:** Rejects forbidden execution metadata keys (`cmd`, `command`, `commands`, `script`, `scripts`, `shell`, `bash`, `sh`, `exec`, `entrypoint`, `run`, `dockerfile`) as well as secret-like metadata keys (`token`, `secret`, `password`, `apikey`, `private_key`, `credential`, etc.) and common credential value signatures (`ghp_`, `ghs_`, `AKIA`, `BEGIN ... PRIVATE KEY`, JWTs).

### `StageDependencyEvaluator` Scheduling Semantics

- **Initial Run Creation:** Every stage starts `PENDING`; stages whose `dependsOn` list is empty are immediately promoted to `READY` (`STAGE_READY` event recorded), while downstream stages remain `PENDING`.
- **Upstream Failure / Skip / Cancel Propagation:** If any required (`allowFailure == false`) predecessor reaches `FAILED`, `SKIPPED`, or `CANCELLED`, downstream `PENDING`/`READY` stages transition deterministically to `SKIPPED`.
- **`allowFailure` Semantics:** A predecessor marked `allowFailure = true` satisfies downstream readiness upon reaching `SUCCEEDED` or `FAILED` (but not `CANCELLED`).
- **Run Terminal Outcome:** When all stages reach terminal states, the evaluator deterministically computes `SUCCEEDED` (no required failures/skips) vs `FAILED` (any required failure).

---

## 6. Idempotency & Concurrency Model

### Database-Backed Idempotency (`PipelineIdempotencyService`)

- Scope: `(workspace_id, actor_user_id, operation="CREATE_PIPELINE_RUN", idempotency_key)`.
- Retention TTL: **24 hours** (expired keys are reclaimed prior to atomic insertion).
- Request Fingerprinting: Canonical SHA-256 hash of `(workspaceId, definitionId, targetBranch, commitSha, triggerType)`.
- Atomic Claim: Native `INSERT INTO pipeline_idempotency_keys (...) VALUES (...) ON CONFLICT DO NOTHING` executes safely across PostgreSQL 17 and H2 PG-mode without aborting surrounding transactions.
- Behavior:
  - First request with key `K` -> creates run, attaches `pipeline_run_id`, returns **`201 Created`**.
  - Repeated request with same key `K` and identical payload (even after backend restart) -> returns existing run with **`200 OK`** and **`X-Idempotent-Replay: true`**.
  - Repeated request with same key `K` and different payload -> fails with **`409 Conflict`**.
  - Concurrent in-flight duplicate request -> returns existing run once committed or a clean retryable **`409 Conflict`**, never creating duplicate runs.

### Concurrency Control

- **Pessimistic Row Locking (`SELECT ... FOR UPDATE`):**
  - `createRun` locks the `pipeline_definitions` row (`findByIdAndWorkspaceIdForUpdate`), guaranteeing strictly monotonic, race-free `run_number` sequences (`1, 2, 3, ...`) and snapshot consistency against concurrent definition edits.
  - `cancelRun` locks the `pipeline_runs` row (`findByIdAndWorkspaceIdForUpdate`), serializing concurrent cancellation attempts so duplicate cancels return idempotently with exactly one `RUN_CANCELLED` event.
- **Optimistic Locking (`@Version` `row_version`):**
  - `pipeline_definitions`, `pipeline_runs`, and `pipeline_stage_runs` carry JPA `@Version` columns.
  - `PUT /pipelines/definitions/{definitionId}` supports explicit `expectedRowVersion` checks and `GlobalExceptionHandler` maps `ObjectOptimisticLockingFailureException` / `OptimisticLockException` to **`409 Conflict`**.

---

## 7. REST API Endpoints & RBAC Matrix

All routes require an authenticated session (`CLOUDFORGE_SESSION`), enforce workspace membership on the backend, validate cross-workspace ownership (`403`/`404`), and enforce SPA CSRF (`X-XSRF-TOKEN`) on all mutating calls.

| Method | Endpoint | Allowed Roles | CSRF | Behavior |
| --- | --- | --- | --- | --- |
| `GET` | `/api/workspaces/{workspaceId}/pipelines/definitions` | `OWNER`, `DEVELOPER`, `VIEWER` | No | Paginated list of workspace pipeline definitions (filterable by `projectId`, `enabled`, `q`). |
| `POST` | `/api/workspaces/{workspaceId}/pipelines/definitions` | `OWNER` | Yes | Validates DAG & security rules, creates definition v1, records `PIPELINE_DEFINITION_CREATED` audit event (`201 Created`). |
| `GET` | `/api/workspaces/{workspaceId}/pipelines/definitions/{definitionId}` | `OWNER`, `DEVELOPER`, `VIEWER` | No | Fetches single definition with ordered stage definitions. |
| `PUT` | `/api/workspaces/{workspaceId}/pipelines/definitions/{definitionId}` | `OWNER` | Yes | Updates definition, increments `definitionVersion`, checks optimistic `expectedRowVersion`, replaces stages cleanly, records `PIPELINE_DEFINITION_UPDATED` audit event (`200 OK`). |
| `GET` | `/api/workspaces/{workspaceId}/pipelines/runs` | `OWNER`, `DEVELOPER`, `VIEWER` | No | Paginated run history (filterable by `projectId`, `definitionId`, `status`, `q`). |
| `POST` | `/api/workspaces/{workspaceId}/pipelines/runs` | `OWNER`, `DEVELOPER` | Yes | Accepts optional `Idempotency-Key` header; creates `QUEUED` run with immutable snapshot & root `READY` stages (`201 Created`), or replays existing run (`200 OK` + `X-Idempotent-Replay: true`). Records `PIPELINE_RUN_REQUESTED` audit event. |
| `GET` | `/api/workspaces/{workspaceId}/pipelines/runs/{runId}` | `OWNER`, `DEVELOPER`, `VIEWER` | No | Fetches single run with immutable snapshot metadata and ordered stage instances. |
| `POST` | `/api/workspaces/{workspaceId}/pipelines/runs/{runId}/cancel` | `OWNER`, `DEVELOPER` | Yes | Transitions active (`QUEUED`/`RUNNING`) run and unstarted stages to `CANCELLED`; idempotent on already-`CANCELLED` runs; rejects terminal `SUCCEEDED`/`FAILED` runs with `409 Conflict`. Records `PIPELINE_RUN_CANCELLED` audit event. |
| `GET` | `/api/workspaces/{workspaceId}/pipelines/runs/{runId}/events` | `OWNER`, `DEVELOPER`, `VIEWER` | No | Returns chronological immutable lifecycle transition events (`RUN_QUEUED`, `STAGE_READY`, `RUN_CANCELLED`, `STAGE_CANCELLED`, etc.). |

CORS (`SecurityConfig`) explicitly permits `Idempotency-Key` in `allowedHeaders` and exposes `X-Idempotent-Replay` alongside `X-Request-Id`.

---

## 8. Cancellation & Audit Integration

- Cancelling a `QUEUED` run transitions the run to `CANCELLED` (recording `cancelledBy`, `cancelledAt`, `completedAt`, and `statusReason`), transitions every non-terminal stage (`PENDING` or `READY`) to `CANCELLED`, appends `RUN_CANCELLED` and `STAGE_CANCELLED` events to `pipeline_run_events`, and writes a `PIPELINE_RUN_CANCELLED` workspace audit event.
- Duplicate cancellation requests on an already-`CANCELLED` run return `200 OK` with the existing state without duplicating events or audit rows.
- Every major mutation writes to `audit_events`: `PIPELINE_DEFINITION_CREATED`, `PIPELINE_DEFINITION_UPDATED`, `PIPELINE_RUN_REQUESTED`, and `PIPELINE_RUN_CANCELLED` (strictly non-secret metadata only).

---

## 9. Phase 3.3 Worker Integration Contract (`WorkerAssignmentContract`)

`com.cloudforge.backend.orchestration.engine.WorkerAssignmentContract` defines the clean internal service seam (`StageDispatchDescriptor` and `listReadyStageTickets(workspaceId, runId)`) that Phase 3.3 isolated build workers will use to lease and execute `READY` stage instances. It carries zero executable shell commands or plaintext secrets and is never exposed directly as a public REST mutation endpoint.

---

## 10. Frontend Integration (`CloudForgePipelinesPanel`)

- Integrated inside `frontend/src/pages/PipelinesPage.tsx` via an accessible `role="tablist"` clearly separating **GitHub Actions** (Phase 3.1 default view) and **CloudForge Pipelines** (Phase 3.2 orchestration view).
- Preserves the PR #6 Obsidian design tokens, dark/light/system theme switching, accent palettes, reduced-motion mode, and responsive mobile drawer navigation.
- Supports:
  - Orchestration summary tiles (Definitions, Orchestrated Runs, Queued Runs, Cancelled Runs).
  - Pipeline Definitions table with stage DAG badges, search/project filtering, and `OWNER`-only **New Pipeline Definition** / **Edit** modal with accessible checkbox-based predecessor selection and backend field error display.
  - **Queue Run** modal (`OWNER`/`DEVELOPER`) with auto-generated editable `Idempotency-Key`, branch/SHA inputs, and clear pre-submit notice explaining `Queued — Awaiting execution infrastructure`.
  - Orchestrated Runs table displaying `Queued — Awaiting execution infrastructure`, stage count breakdown (`ready` / `pending` / `cancelled`), **Inspect** modal (stage dependency cards + chronological `pipeline_run_events` timeline), and confirmation-gated **Cancel** modal.
  - Strict role-based UI controls (`VIEWER` read-only; `DEVELOPER` run operations only; `OWNER` full definition + run control).

---

## 11. Verification & Test Coverage

Verification commands executed on branch `feat/phase3-2-pipeline-orchestration`:

- **Backend (`cd backend && mvn -B test && mvn -B package -DskipTests`):** `52` tests run, `47` passed locally, `0` failures, `0` errors, `5` skipped (`PostgresSearchQueryRegressionTest` skipped locally when PostgreSQL 17 is not running on `127.0.0.1:5432`; executed in GitHub Actions CI against `postgres:17-alpine`); `cloudforge-backend.jar` packaged cleanly (`BUILD SUCCESS`).
- **Frontend (`cd frontend && npm ci && npm run typecheck && npm test && npm run build`):** `npm ci` (`0` vulnerabilities), `tsc -b --noEmit` (`0` errors), Vitest `4` test files passed / `23` tests passed (`0` failed, `0` skipped — preserving all `17` Phase 1–3.1 tests plus `6` new Phase 3.2 orchestration UI tests), and production Vite bundle built cleanly.
- **Docker Compose (`docker compose config --quiet`):** Validated cleanly (exit code `0`).
- **Real PostgreSQL 17 CI Suite:** GitHub Actions CI runs the full backend suite including all 5 tests in `PostgresSearchQueryRegressionTest` (covering `V4` Flyway migration, `ON CONFLICT DO NOTHING` idempotency replay, Criteria null/`%` searches, and pessimistic-locked cancellation) against `postgres:17-alpine`.

---

## 12. Deployment Prerequisites & Known Limitations

### Deployment Prerequisites

1. PostgreSQL 17 database reachable via existing `SPRING_DATASOURCE_*` environment variables.
2. Flyway runs `V4__pipeline_orchestration_engine.sql` automatically on startup without touching `V1`–`V3` data.
3. No new external credentials, GitHub App permissions, or Render environment variables are required for Phase 3.2.

### Known Limitations (Planned for Phases 3.3–3.7)

- **No Stage Execution Yet (Phase 3.3):** Queued runs remain `QUEUED` and root stages remain `READY` until isolated, rootless ephemeral build workers with CPU/memory/disk/network/timeout sandboxing are implemented in Phase 3.3.
- **No Encrypted Secrets Vault Yet (Phase 3.4):** Stage metadata continues to prohibit plaintext secrets until KMS / External Secrets integration arrives.
- **No Container Registry Publishing or Kubernetes Rollouts Yet (Phases 3.5–3.7):** Deployment stages are modeled declaratively in DAG definitions only and do not deploy workloads.
- **No Automated Webhook-Triggered Pipeline Runs Yet:** Webhooks remain strictly Phase 2 repository lifecycle sync; automated push/PR orchestration triggers will be wired with Phase 3.3 execution infrastructure.
