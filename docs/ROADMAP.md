# CloudForge Development Roadmap

This roadmap clearly distinguishes **Implemented (Phases 1–2, Phases 3.1–3.5)** functionality from **Planned (Phases 3.6–4)** capabilities.

---

## Phase 1 — Platform Foundation (**Implemented**)

- [x] Structured monorepo layout (`frontend/`, `backend/`, `infrastructure/`, `docs/`, `.github/workflows/`).
- [x] Premium dark-themed React + TypeScript + Vite web console with responsive sidebar navigation and 7 views (Dashboard, Projects, Repositories, Deployments, Pipelines, Monitoring, Settings).
- [x] Reusable UI component library (`Sidebar`, `Header`, `StatusBadge`, `DashboardCard`, `PageContainer`, `LoadingState`, `ErrorState`, `DataTable`).
- [x] Vite development proxy routing `/api/*` and `/actuator/*` to Spring Boot.
- [x] Java 17 Maven Spring Boot 3.4 backend with modular package architecture (`controller`, `service`, `repository`, `entity`, `dto`, `config`, `exception`).
- [x] Live `GET /api/health` endpoint performing a real JDBC `SELECT 1` connectivity probe against PostgreSQL, returning `200 OK` (`UP`) or `503 Service Unavailable` (`DEGRADED`).
- [x] Spring Boot Actuator endpoints (`/actuator/health`, `/actuator/info`) and structured MDC-correlated logging (`X-Request-Id`).
- [x] PostgreSQL 17 in Docker Compose with named persistent volume, `pg_isready` health check, environment-variable configuration, and localhost-only port binding.
- [x] Multi-stage backend `Dockerfile` with non-root Alpine Temurin 17 runtime.
- [x] Automated backend (JUnit 5 / MockMvc / SpringBootTest) and frontend (Vitest / React Testing Library) test suites plus GitHub Actions CI workflow.
- [x] Comprehensive `.gitignore`, `.env.example`, `README.md`, and architectural documentation.

---

## Phase 2 — Identity, GitHub Integration & Persistent Workspaces (**Implemented**)

- [x] **Database Migrations:** Flyway versioned migrations (`V1__phase2_core_schema.sql`, `V2__webhook_deliveries.sql`) under `backend/src/main/resources/db/migration/` managing `users`, `workspaces`, `workspace_members`, `github_installations`, `linked_repositories`, `projects`, `project_environments`, `audit_events`, and `webhook_deliveries` on top of `BaseAuditableEntity`.
- [x] **Authentication & Session Security:** Spring Security GitHub OAuth 2.0 authorization-code login (`read:user`, `user:email`), HTTP-only server-side sessions (`CLOUDFORGE_SESSION`), SPA CSRF protection (`XSRF-TOKEN` / `X-XSRF-TOKEN`), open-redirect allowlist validation, `GET /api/auth/me`, `GET /api/auth/config`, `GET /api/auth/csrf`, and `POST /api/auth/logout`.
- [x] **Workspace RBAC:** Backend-enforced `OWNER`, `DEVELOPER`, and `VIEWER` workspace roles with cross-workspace isolation and last-owner protection.
- [x] **GitHub App Integration:** Dedicated GitHub App RS256 JWT signing (`GitHubAppTokenService`), short-lived in-memory installation access tokens (`metadata: read`), single-use session state callback validation, ownership validation, repository discovery/sync, and revoked/suspended/rate-limit handling.
- [x] **Verified GitHub Webhooks:** `POST /api/webhooks/github` with constant-time HMAC-SHA256 verification over raw request bytes, database-backed delivery deduplication, and Phase 2 lifecycle synchronization (builds/deployments explicitly disabled).
- [x] **Projects & Persistent Environments:** Full PostgreSQL-backed Projects CRUD, repository linking, and `Development`/`Staging`/`Production` non-secret configuration management with active secret-pattern rejection.
- [x] **Audit Trail:** Immutable workspace audit history recorded for auth, membership, project, environment, GitHub App, and webhook events (`OWNER`-restricted query API + UI).
- [x] **Full Frontend Integration:** Login screen, protected routes, workspace switcher, user profile & sign-out controls, live PostgreSQL dashboard counts, Projects CRUD & Detail/Environments editor, Repositories discovery/linking, and Settings RBAC/Audit views.
- [x] **Automated Verification & Setup Guide:** Comprehensive backend + frontend test suites and dedicated `docs/GITHUB_INTEGRATION.md`. *(Live external GitHub OAuth/App verification requires supplying your own GitHub credentials in `.env`.)*

---

## Phase 3.1 — Read-Only GitHub Actions Integration & Pipelines Dashboard (**Implemented**)

- [x] **PostgreSQL Persistence (`V3__github_actions_workflows.sql`):** Non-destructive Flyway migration adding `github_workflows` and `github_workflow_runs` with run-attempt uniqueness (`linked_repository_id`, `github_run_id`, `run_attempt`), status check constraints, and query indexes.
- [x] **Least-Privilege GitHub Actions Client (`actions: read`):** Scoped GitHub App installation tokens preserving `metadata: read` for Phase 2 repository sync while requesting strictly `metadata: read` + `actions: read` for paginated workflow, run, and job retrieval (`RestClientGitHubApiClient`).
- [x] **Idempotent Synchronization Service (`GitHubActionsSyncService`):** Short-transaction sync lifecycle (no DB transactions held across GitHub HTTP requests), per-repository concurrency guard, run-attempt tracking, and clean handling of missing `Actions: Read` permissions, rate limits (`Retry-After`), revoked/suspended installations, and missing repositories.
- [x] **Secure Workspace-Isolated REST APIs (`PipelineController`):** Authenticated, workspace-scoped `/pipelines`, `/workflows`, `/workflow-runs`, `/workflow-runs/{runId}`, `/workflow-runs/{runId}/jobs`, and CSRF-protected `POST .../workflows/sync` (`OWNER`/`DEVELOPER` only).
- [x] **Security Hardening:** Root `.dockerignore`, atomic `ON CONFLICT DO NOTHING` webhook deduplication prior to side effects, fail-closed unverified new Organization installation callback guard, and pre-lookup membership enforcement on environment updates.
- [x] **Premium Pipelines Dashboard (`PipelinesPage`):** Live telemetry summary, searchable repository selector, discovered workflows table, filtered/paginated workflow runs table, accessible Run Details modal with live ordered jobs & expandable steps, clear distinction between **Refresh Stored Data** vs **Sync from GitHub**, and full dark/light/system/accent/mobile responsiveness. See `docs/PHASE3_1_GITHUB_ACTIONS.md`.

---

## Phase 3.2 — Pipeline Orchestration Engine (**Implemented**)

- [x] **PostgreSQL Persistence (`V4__pipeline_orchestration_engine.sql`):** Non-destructive Flyway migration adding `pipeline_definitions`, `pipeline_stage_definitions`, `pipeline_runs`, `pipeline_stage_runs`, `pipeline_run_events`, and `pipeline_idempotency_keys` with UUID primary keys, optimistic locking (`row_version`), status/type check constraints, monotonic `(pipeline_definition_id, run_number)` uniqueness, and workspace-scoped indexes.
- [x] **Declarative Pipeline Definitions & DAG Validator (`StageDagValidator`):** Project-scoped pipeline definitions (`SOURCE_PREP`, `BUILD`, `TEST`, `SECURITY_SCAN`, `CONTAINER_PACKAGE`, `DEPLOYMENT`), strict Kahn topological sort rejecting cycles, self-dependencies, missing predecessors, duplicate keys, oversized graphs (>25 stages / >10 dependencies), shell-command fields, and plaintext secret patterns.
- [x] **Encapsulated State Machines & Dependency Evaluation (`PipelineStateMachine`, `StageStateMachine`, `StageDependencyEvaluator`):** Explicit transition enforcement preventing illegal or terminal-state mutations, immutable definition snapshots per run, deterministic root-stage `READY` promotion, transitive downstream failure/skip/cancel propagation, and `allowFailure` support.
- [x] **Database-Backed Idempotency & Concurrency Control (`PipelineIdempotencyService`, `PipelineOrchestrationService`):** Restart-safe `Idempotency-Key` support (24-hour TTL, SHA-256 request fingerprinting, atomic `INSERT ... ON CONFLICT DO NOTHING`, `409 Conflict` on payload mismatch), optimistic locking (`@Version`), and pessimistic row locking (`SELECT ... FOR UPDATE`) for monotonic run numbering and race-safe cancellation.
- [x] **Workspace-Isolated REST APIs (`PipelineOrchestrationController`):** Nine authenticated, workspace-scoped endpoints under `/api/workspaces/{workspaceId}/pipelines` for definition CRUD (`OWNER`), run queueing/cancellation (`OWNER`/`DEVELOPER` with CSRF), and run/event inspection (`OWNER`/`DEVELOPER`/`VIEWER`), plus an internal `WorkerAssignmentContract` reserved strictly for Phase 3.3 workers (no fake execution, no user-facing execution triggers).
- [x] **Premium Frontend Integration (`CloudForgePipelinesPanel` inside `PipelinesPage`):** Dedicated **CloudForge Pipelines** tab cleanly separated from **GitHub Actions**, supporting definition creation/editing with accessible DAG dependency builder, `Idempotency-Key` run queueing, honest `Queued — Awaiting execution infrastructure` state presentation, run/stage/event history inspection, and confirmation-gated cancellation. See `docs/PHASE3_2_PIPELINE_ORCHESTRATION.md`.

---

## Phase 3.3 — Isolated Build Workers, Secure Execution & Pipeline Scheduling (**Implemented — Local/Dev Allowlisted Operations**)

- [x] **Two-Domain Separation (Control Plane vs. Execution Plane):** Standalone Java 17 worker module (`worker/`, `cloudforge-worker.jar`) communicating exclusively with authenticated control-plane endpoints (`/api/internal/workers/**`) — zero Docker execution or Docker socket mounts inside the Spring Boot web server or Render web service.
- [x] **PostgreSQL Persistence (`V5__isolated_worker_execution.sql`):** Non-destructive Flyway migration extending `pipeline_runs` / `pipeline_stage_runs` and creating `worker_nodes`, `worker_credentials` (SHA-256 hashed tokens only), `stage_execution_attempts` (leases, fencing generations, outcomes), and `stage_execution_logs` (bounded, ordered, sanitized logs).
- [x] **Trusted Worker Authentication & Fail-Closed Defaults:** Dedicated stateless Spring Security filter chain (`@Order(1)`) validating per-worker `X-CloudForge-Worker-Id` + `Authorization: Bearer` tokens with constant-time SHA-256 comparison, expiration/revocation support, spoofing prevention (`403`), ordinary-user exclusion, and `cloudforge.workers.enabled=false` default (locked `false` in `application-render.yml`).
- [x] **Bounded FIFO Scheduler, Atomic Leasing & Fencing:** `WorkerSchedulerService`, `WorkerResultProcessorService`, and `WorkerRecoveryService` enforcing deterministic `Worker -> Run -> Stage -> Attempt` pessimistic locks, unique `leaseToken` + monotonic `fencingGeneration`, retry backoff via `RUNNING -> READY` (preserving terminal `FAILED` immutability), active cancellation signaling requiring confirmed termination, and stale lease recovery.
- [x] **Hardened Local Docker Execution Adapter (`DockerIsolatedExecutionAdapter`):** Runs strictly allowlisted operations (`ENV_VALIDATION`, `CONTROLLED_SMOKE_TEST`, `WORKER_DIAGNOSTICS`) inside ephemeral containers (`--rm`, `--user 65534:65534`, `--cap-drop=ALL`, `no-new-privileges:true`, `--read-only`, bounded `noexec` `/tmp`, `--network=none`, CPU/memory/PID limits, pinned/allowlisted images only, confirmed cleanup). Unsupported stages (`BUILD`, `SECURITY_SCAN`, `CONTAINER_PACKAGE`, `DEPLOYMENT`) report explicit capability boundaries rather than faking success.
- [x] **Frontend Worker Telemetry & Log Viewer (`CloudForgePipelinesPanel`):** Live worker pool availability card, allowlisted operation selector in definition editor, honest `QUEUED — Awaiting available worker` / `Cancelling — Awaiting worker termination` badges, per-stage execution attempt history, and bounded monospace sanitized execution logs. See `docs/PHASE3_3_ISOLATED_WORKERS.md`.

---

## Phase 3.4 — Encrypted Secret Management, Key Management & Secure Worker Delivery (**Implemented**)

- [x] **AES-256-GCM Envelope Encryption (`SecretCryptoService` & `EnvKeyringSecretKeyProvider`):** Fresh 256-bit DEKs, fresh 96-bit CSPRNG nonces, 128-bit GCM auth tags, deterministic length-prefixed AAD binding workspace/project/environment/secret/version/format, external 256-bit Base64 KEK validation, historical key support, and fail-closed unconfigured states (`CLOUDFORGE_SECRETS_ENABLED=false` default).
- [x] **PostgreSQL Persistence (`V6__encrypted_secret_management.sql`):** Non-destructive Flyway migration adding `environment_secrets`, `environment_secret_versions` (`BYTEA` ciphertext/nonce/wrapped_dek + `octet_length(nonce) = 12` constraint), `pipeline_run_secret_references`, and `secret_key_rotation_runs`.
- [x] **Write-Only REST APIs & Conservative RBAC:** `OWNER`-restricted metadata CRUD, version rotation, version/secret revocation, soft deletion, `Cache-Control: no-store`, CSRF protection, and cooldown rate-limited `SECRET_ACCESS_DENIED` auditing. Non-secret environment variables remain cleanly separated and continue blocking plaintext secrets.
- [x] **Pipeline Secret Bindings & Queue-Time Pinning:** Validated single-environment stage secret bindings restricted to allowlisted worker operations (`SOURCE_PREP` / `TEST`), immutable version pinning in `pipeline_run_secret_references` without decrypting at queue time, and `OWNER`-only queueing for secret-bearing pipelines.
- [x] **Lease-Bound Worker Delivery, Operator Key Rotation & Multi-Layer Redaction:** Fenced internal worker delivery endpoint (`/api/internal/workers/{workerId}/attempts/{attemptId}/secrets`) disabled by default (`worker-delivery-enabled=false`, locked off in Render), DEK-only batch master key rotation (`/api/internal/operator/secrets/keys/**`), ephemeral stdin-only secret injection in `worker/`, and raw/multiline/Base64/URL-encoded/cross-chunk boundary redaction across worker & control plane.
- [x] **Premium Frontend Integration:** Dedicated `EnvironmentSecretsPanel` inside `ProjectDetailPage` (metadata-only table, masked write-only modals cleared immediately on close/submit, version history, revoke/delete confirmations) and Target Environment + secret-reference selector inside `CloudForgePipelinesPanel`. See `docs/PHASE3_4_SECRET_MANAGEMENT.md`.

---

## Phase 3.5 — Secure Container Builds, OCI Registry Publishing & Immutable Artifact Management (**Implemented — Trusted-Repository BuildKit Mode**)

- [x] **Phase 3.5A — Immutable GitHub Source Acquisition (`V7__container_builds_oci_registry.sql`):** Queue-time resolution of branch/tag refs to immutable 40-character GitHub commit SHAs, single-grant HTTPS archive issuance, dedicated `GITHUB_SOURCE` worker, bounded ZIP extraction (32 MiB ZIP / 128 MiB extracted / 12,000 entries), symlink/path-traversal rejection, and verified canonical ZIP build-context handoff (`oci_source_handoffs`).
- [x] **Phase 3.5B — PostgreSQL Persistence (`V8__oci_builds_and_registry_artifacts.sql`):** Non-destructive Flyway migration extending `worker_nodes` (`OCI_BUILDKIT`) and `stage_execution_attempts` (6 OCI failure categories) and adding `oci_registry_connections`, `oci_source_handoffs`, `oci_build_records`, `oci_image_artifacts`, `oci_publish_attempts`, and `oci_artifact_events` (31 application tables total).
- [x] **Declarative OCI Build Config & Defensive Dockerfile Validation (`BuildRequestValidator`, `DockerfilePolicyValidator`):** Rejects path traversal, absolute host paths, remote URI contexts, Docker daemon endpoints, privileged modes, untrusted base images/frontends, remote `ADD` URLs, `RUN --security=insecure` / `--network=host` / `--mount=type=ssh|secret`, and secret-like build arguments.
- [x] **Dedicated Bounded BuildKit Executor (`OCI_BUILDKIT` / `BuildKitExecutionAdapter` & `OciLayoutInspector`):** Produces local verified OCI Image Layout TAR archives (`--output type=oci`, `--network=none` default, zero privileged entitlements, zero Docker socket inside backend or build containers), strictly separating Dockerfile execution (`OCI_IMAGE_BUILD`, zero credentials) from registry publishing (`OCI_IMAGE_PUBLISH`).
- [x] **Standards-Based OCI Distribution v1.1 Publishing & Immutable Digest Verification (`OciRegistryClient`, `RegistryCredentialResolver`, `OciBuildLifecycleService`):** Resolves workspace registry credentials strictly via Phase 3.4 AES-256-GCM encrypted secrets, enforces HTTPS & SSRF IP/DNS protection, disables redirects, zeroes credential buffers in memory, independently retrieves pushed manifests by digest to verify raw SHA-256 equality, persists idempotent `oci_image_artifacts`, and marks expired non-idempotent publish attempts `RECONCILIATION_REQUIRED`.
- [x] **Premium Frontend Integration (`OciArtifactsPanel` & `CloudForgePipelinesPanel`):** Dedicated **Builds & Artifacts** tab inside `PipelinesPage` showing honest OCI status, verified immutable artifacts with copyable digests and artifact detail modals, `OWNER`-only registry connection management referencing Phase 3.4 encrypted secrets, OCI build history, declarative OCI stage editor, and run-linked artifacts. See `docs/PHASE3_5_CONTAINER_BUILDS_OCI_REGISTRY.md`.

---

## Phases 3.6–3.7 — Hardened Multi-Tenant Sandboxing, Kubernetes & Rollback (**Planned**)

- [ ] **MicroVM / Hardened Sandbox Isolation:** Introduce gVisor / Kata / Firecracker sandboxed execution before permitting hostile/untrusted multi-tenant repository builds.
- [ ] **Phase 3.6 — Kubernetes Orchestration (`infrastructure/kubernetes/`):** Deploy immutable verified OCI image digests to controlled local (kind/k3d) and remote Kubernetes namespaces using least-privilege service accounts, readiness/liveness probes, and `NetworkPolicy` isolation.
- [ ] **Phase 3.7 — Deployment History & Rollback:** Persist every release revision (commit SHA, image digest, manifest checksum, actor, outcome) and provide one-click rollback to previously verified healthy releases.

---

## Phase 4 — Observability & DevSecOps Automation (**Planned**)

- [ ] **Prometheus Integration (`infrastructure/monitoring/`):** Expose `/actuator/prometheus` via Micrometer and scrape API, PostgreSQL, build worker, and Kubernetes cluster metrics.
- [ ] **Grafana Dashboards & Alerting:** Ship version-controlled Grafana dashboards and alerting rules for latency, error rates, database pool exhaustion, and failed rollouts.
- [ ] **DevSecOps Policy Gates:** Automate secret scanning, Maven/npm dependency CVE checks, container image scanning (Trivy), SBOM generation (Syft), and image signing (Cosign) prior to Kubernetes promotion.
- [ ] **Production Frontend Container & Ingress:** Add hardened NGINX/static frontend image and TLS ingress templates under `infrastructure/docker/` and `infrastructure/kubernetes/`.
