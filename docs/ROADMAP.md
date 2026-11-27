# CloudForge Development Roadmap

This roadmap tracks the completed **Phases 1–4** engineering scope and clearly separates **Implemented in GitHub** from **Verified Locally / in CI**, **Enabled in Render Staging**, and **Production Hardening Required**.

---

## Phase 1 — Platform Foundation (**Implemented & Verified**)

- [x] Structured monorepo layout (`frontend/`, `backend/`, `worker/`, `infrastructure/`, `docs/`, `.github/workflows/`).
- [x] Premium React 19 + TypeScript + Vite web console with Dark/Light/System theme switching, 5 accent palettes, responsive sidebar navigation, accessibility support, and reduced-motion support across all views (Login, Dashboard, Projects, Project Detail, Repositories, Deployments, Pipelines, Monitoring, Settings).
- [x] Reusable UI component library (`Sidebar`, `Header`, `StatusBadge`, `DashboardCard`, `PageContainer`, `LoadingState`, `ErrorState`, `DataTable`, `Modal`, `Toast`, `SearchField`, `ValidatedForm`).
- [x] Vite development proxy routing `/api/*`, `/oauth2/*`, `/login/oauth2/*`, and `/actuator/*` to Spring Boot with `X-Forwarded-*` headers.
- [x] Java 17 Maven Spring Boot 3.5 backend with modular package architecture (`controller`, `service`, `repository`, `entity`, `dto`, `config`, `exception`, `security`, `github`, `orchestration`, `worker`, `secrets`, `source`, `oci`, `k8s`, `monitoring`, `supplychain`).
- [x] Live `GET /api/health` endpoint performing a real JDBC `SELECT 1` connectivity probe against PostgreSQL, returning `200 OK` (`UP`) or `503 Service Unavailable` (`DEGRADED`).
- [x] Spring Boot Actuator endpoints (`/actuator/health`, `/actuator/info`) and structured MDC-correlated logging (`X-Request-Id`).
- [x] PostgreSQL 17 in Docker Compose with persistent named volume, `pg_isready` health check, environment-variable configuration, and localhost-only port binding (`127.0.0.1:5432`).
- [x] Multi-stage backend `Dockerfile`, Render single-origin `Dockerfile.render`, and automated GitHub Actions CI workflow (`.github/workflows/ci.yml`).

---

## Phase 2 — Identity, GitHub Integration & Persistent Workspaces (**Implemented & Verified**)

- [x] **Database Migrations (`V1`, `V2`):** Flyway versioned migrations managing `users`, `workspaces`, `workspace_members`, `github_installations`, `linked_repositories`, `projects`, `project_environments`, `audit_events`, and `webhook_deliveries` on top of `BaseAuditableEntity`.
- [x] **Authentication & Session Security:** Spring Security GitHub OAuth 2.0 authorization-code login (`read:user`, `user:email`), HTTP-only server-side sessions (`CLOUDFORGE_SESSION`), SPA CSRF protection (`XSRF-TOKEN` / `X-XSRF-TOKEN`), open-redirect allowlist validation, `GET /api/auth/me`, `GET /api/auth/config`, `GET /api/auth/csrf`, and `POST /api/auth/logout`.
- [x] **Workspace RBAC:** Backend-enforced `OWNER`, `DEVELOPER`, and `VIEWER` workspace roles with cross-workspace isolation and last-owner protection.
- [x] **GitHub App Integration:** Dedicated GitHub App RS256 JWT signing (`GitHubAppTokenService`), short-lived in-memory installation access tokens (`metadata: read`), single-use session state callback validation, ownership validation, repository discovery/sync, and revoked/suspended/rate-limit handling.
- [x] **Verified GitHub Webhooks:** `POST /api/webhooks/github` with constant-time HMAC-SHA256 verification over raw request bytes, atomic `ON CONFLICT DO NOTHING` delivery deduplication, and installation/repository lifecycle synchronization.
- [x] **Projects & Persistent Environments:** Full PostgreSQL-backed Projects CRUD, repository linking, and `Development`/`Staging`/`Production` non-secret configuration management with active secret-pattern rejection.
- [x] **Audit Trail:** Immutable workspace audit history recorded across auth, RBAC, projects, environments, GitHub integrations, pipelines, secrets, OCI, Kubernetes, and security evidence (`OWNER`-restricted query API + UI).

---

## Phase 3 — Orchestration, Workers, Secrets, OCI Builds, Kubernetes & Rollback (**Implemented & Verified**)

### Phase 3.1 — Read-Only GitHub Actions Integration (`V3`)
- [x] PostgreSQL persistence for `github_workflows` and `github_workflow_runs` with run-attempt uniqueness.
- [x] Least-privilege `actions: read` GitHub App installation tokens, short-transaction idempotent sync (`GitHubActionsSyncService`), live job/step inspection, and workspace-isolated REST APIs.
- [x] Premium **GitHub Actions** tab in `PipelinesPage`.

### Phase 3.2 — Persistent Pipeline Orchestration Engine (`V4`)
- [x] PostgreSQL persistence for `pipeline_definitions`, `pipeline_stage_definitions`, `pipeline_runs`, `pipeline_stage_runs`, `pipeline_run_events`, and `pipeline_idempotency_keys`.
- [x] Declarative DAG validator (`StageDagValidator`), explicit state machines (`PipelineStateMachine`, `StageStateMachine`, `StageDependencyEvaluator`), database-backed `Idempotency-Key` support, optimistic/pessimistic locking, and confirmation-gated cancellation.
- [x] Premium **CloudForge Pipelines** tab in `PipelinesPage`.

### Phase 3.3 — Isolated Workers, Leasing & Fencing (`V5`)
- [x] Strict two-domain separation between Control Plane (`backend/`) and standalone Execution Plane (`worker/target/cloudforge-worker.jar`).
- [x] Dedicated stateless `@Order(1)` `WorkerAuthenticationFilter` (`/api/internal/workers/**`) with SHA-256 constant-time per-worker tokens, fail-closed `cloudforge.workers.enabled=false` default (locked `false` in `application-render.yml`).
- [x] Bounded FIFO scheduler, deterministic pessimistic lock order, unique `leaseToken` + monotonic `fencingGeneration`, retry backoff, active cancellation signaling, lease-expiry recovery, sanitized bounded execution logs, and hardened Docker container isolation (`--user 65534:65534`, `--cap-drop=ALL`, `no-new-privileges`, `--read-only`, `--network=none`).

### Phase 3.4 — Encrypted Secret Management & Lease-Bound Delivery (`V6`)
- [x] AES-256-GCM envelope encryption (`SecretCryptoService` + `EnvKeyringSecretKeyProvider`) with per-version 256-bit DEKs, 96-bit CSPRNG nonces, deterministic length-prefixed AAD, and fail-closed defaults.
- [x] `OWNER`-only write-only CRUD, version rotation, revocation, soft deletion, `Cache-Control: no-store`, cooldown-protected `SECRET_ACCESS_DENIED` auditing, queue-time immutable version pinning, lease-bound worker delivery (`worker-delivery-enabled=false` default, locked `false` in Render), operator DEK rewrapping (`/api/internal/operator/secrets/**`), and multi-layer secret redaction.
- [x] Premium `EnvironmentSecretsPanel` inside `ProjectDetailPage`.

### Phase 3.5 — Immutable Source Acquisition, OCI Builds & Registry Publishing (`V7`, `V8`)
- [x] Queue-time branch/tag resolution to immutable 40-character GitHub commit SHAs and dedicated `GITHUB_SOURCE` worker with bounded ZIP extraction and path-traversal/symlink rejection.
- [x] Declarative OCI build validation (`BuildRequestValidator`, `DockerfilePolicyValidator`), dedicated `OCI_BUILDKIT` worker producing local OCI Image Layout TAR archives (`--network=none` default, zero credentials during build), standards-based OCI Distribution v1.1 publishing (`OciRegistryClient`) with encrypted registry credentials, independent manifest SHA-256 digest re-verification, and `RECONCILIATION_REQUIRED` handling.
- [x] Premium **Builds & Artifacts** tab (`OciArtifactsPanel`) in `PipelinesPage`.

### Phase 3.6 — Secure Kubernetes Deployment Engine (`V9`)
- [x] Zero `kubectl`, kubeconfig mounts, or Kubernetes client dependencies in `backend/`; all Kubernetes flags default `false` and are locked `false` in `application-render.yml`.
- [x] `OWNER`-only cluster connections with HTTPS + explicit X.509 CA PEM pinning, SSRF/host allowlist enforcement, Phase 3.4 encrypted ServiceAccount bearer tokens, worker-side `SelfSubjectAccessReview` validation rejecting cluster-admin, and operator-approved namespace templates (`infrastructure/kubernetes/`).
- [x] Declarative `apps/v1 Deployment` + `v1 ClusterIP Service` consuming verified `@sha256:` artifacts, identical backend/worker SHA-256 manifest checksums, `KUBERNETES_DEPLOY` worker (`io.kubernetes:client-java:21.0.2`), Server-Side Apply (`force=false`), ownership-label overwrite guard, pod diagnostics, strict readiness + digest verification, explicit `OWNER` production approval gate, and `RECONCILIATION_REQUIRED` crash handling.
- [x] Premium `DeploymentsPage` console.

### Phase 3.7 — Release History, Verified Rollback & Reconciliation (`V10`)
- [x] Monotonic target-scoped release revisions (`revision_number`), operation kinds (`DEPLOYMENT`, `ROLLBACK`, `RECONCILIATION`), structured release diffs, and artifact retention protection (`REFERENCED_PROTECTED`).
- [x] Preview-bound `expectedOperationId` optimistic preconditions, worker resource UID/generation verification, `ROLLBACK:`/`RECONCILIATION:` prefixed checksum fail-closed fencing, read-only live cluster reconciliation, and `ReleaseHistoryPanel` UI.

---

## Phase 4 — Observability, DevSecOps & Platform Hardening (**Implemented & Verified**)

- [x] **Private Prometheus Telemetry (`application-monitoring.yml`, `ScrapeSecurity`, `OperationMetrics`):** Exposes `/actuator/prometheus` strictly under the `monitoring` profile behind a `>= 32`-char constant-time Bearer token check (`GET` only); exports JVM, HTTP histogram, HikariCP, and `cloudforge_operations_total` domain counters plus ECS structured console logs.
- [x] **Worker Prometheus Exporter (`WorkerTelemetry`):** Cross-platform atomic `cloudforge.prom` textfile emission scraped by `node-exporter` for heartbeat age, active assignments, failure totals, and execution duration.
- [x] **Monitoring Stack (`compose.monitoring.yaml`, `infrastructure/monitoring/`):** Provisioned Prometheus v3.5.0, Alertmanager v0.28.1, Grafana 12.1.1 with 5 version-controlled dashboards, 8 alert rules, PostgreSQL exporter, Worker exporter, and optional OpenTelemetry Collector.
- [x] **Signed Supply-Chain Evidence & Production Gate (`V11`, `ArtifactEvidenceService`, `ArtifactEvidenceController`, `assess-image.sh`):** Persists ECDSA-signed Trivy vulnerability + CycloneDX SBOM + Cosign verification envelopes (`artifact_security_evidence`), supports both raw DER Base64 and standard Cosign PEM public keys, enforces `OWNER`-only CSRF submission, and blocks production Kubernetes execution unless current (<= 24h) signed zero-high/critical evidence exists.
- [x] **Hardened Frontend Container & Kubernetes Ingress (`frontend/Dockerfile`, `frontend/nginx.conf`, `infrastructure/kubernetes/frontend/frontend.yaml`):** Non-root patched Alpine Nginx serving SPA deep links with `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `/assets/` immutable caching, and `404` on `/api`, `/oauth2`, `/login/oauth2`, and `/actuator`.
- [x] **Supply-Chain CI Gates (`.github/workflows/ci.yml`):** Automated `promtool`/`amtool`/dashboard validation, frontend container deep-link test, Trivy CycloneDX SBOM + Cosign blob sign/verify/tamper check, and Trivy source + image `HIGH,CRITICAL` zero-finding enforcement.
- [x] **Final Stabilization & Release Readiness (`1.0.0`):** Cross-platform `WorkerTelemetry` fix, ECS logback profile wiring, broken alert runbook link repair, PEM key parsing, SPA `Cache-Control: no-cache`, Render 512 MiB heap tuning, fresh + V2-to-V11 migration + `pg_dump`/`pg_restore` verification, and final capstone documentation.

---

## Post-Capstone Production Hardening (Required Before Untrusted Public Production)

- [ ] **MicroVM / Hardened Sandbox Worker Isolation:** Evaluate gVisor, Kata Containers, or Firecracker before permitting untrusted multi-tenant repository builds.
- [ ] **External KMS & Short-Lived Cluster Credentials:** Migrate `EnvKeyringSecretKeyProvider` to cloud KMS/HSM (AWS KMS, GCP KMS, Vault Transit) and automate Kubernetes/registry credential rotation.
- [ ] **Automated In-Pipeline Security Scanning:** Integrate automated `SECURITY_SCAN` stage workers (Trivy/Syft/Cosign) directly into DAG execution in addition to the operator-side `assess-image.sh` workflow.
- [ ] **Multi-Party Production Approval & HA Control Plane:** Add two-person approval for production rollouts/rollbacks, externalized session store (Redis/JDBC sessions) for multi-replica backend scaling, and paid managed PostgreSQL with PITR backups before the free Render database expires on December 23, 2026.
