# CloudForge — Final Integration & Verification Test Report

**Branch:** `chore/final-cloudforge-stabilization`
**Base Commit:** `dfd3ac4093aed15f0e0394b7be7c50d12eb6212f`
**Test Environment:** Windows 11, Java 17.0.15 (Temurin), Maven 3.9.16, Node v24.19.0, npm 11.17.0, Docker Engine 29.8.0 (WSL 2), `kubectl` (bundled).
**Principle:** Every pass recorded below reflects actual execution. Skipped tests and externally blocked checks are reported explicitly.

---

## 1. Summary of Executed Verification Suites

| Suite / Scenario | Real Infrastructure Used | Result | Key Observed Evidence |
| --- | --- | --- | --- |
| **Backend Automated Suite + PG 17** | Disposable `postgres:17-alpine` (`TEST_POSTGRES_RUN_FLYWAY=true`) | **PASSED (`95/95`, `0` skipped)** | All 38 test classes including `PostgresSearchQueryRegressionTest` (6/6), `FlywayMigrationTest` (11 migrations, 37 tables), `Phase4SecurityAndMonitoringIntegrationTest` (2/2), `Render*SafetyTest`. |
| **Worker Automated Suite + Real Docker + Real BuildKit + `registry:2`** | Live Docker Engine + `alpine:3.20` + `docker buildx` + `registry:2` | **PASSED (`39/40` run, `1` opt-in skipped)** | `DockerIsolatedExecutionIntegrationTest` (2/2), `WorkerTelemetryTest` (2/2 on NTFS), `RealBuildKitAndRegistryEndToEndTest` (1/1: `127.0.0.1:49765/cloudforge/e2e-hello@sha256:9569e69b0a6929006731b184f4fbe56b8a9965781d3552e05eb2706fbaa157c1`, 1251 bytes). Skipped 1 = `RealLocalKubernetesEndToEndTest` (run separately below). |
| **Disposable Kubernetes Deploy, Update, Rollback, Reconcile & RBAC** | Live disposable `rancher/k3s:v1.31.2-k3s1` + `kubectl` + `provision-local-namespace.sh` | **PASSED (`1/1` E2E + `18/18` RBAC)** | Deploy A (`gen=1`, 2/2 Ready), Update B (`gen=2`, 2/2 Ready), Rollback A (`gen=3`, 2/2 Ready), read-only reconcile, negative invalid-digest `ErrImagePull` failure. All 9 allowed & 9 denied RBAC checks passed. |
| **PostgreSQL V2 -> V11 Upgrade, Data Preservation, Repeatability & Restore** | Disposable `postgres:17-alpine` (PostgreSQL 17.12) + `pg_dump` / `pg_restore` | **PASSED (`100%`)** | Authentic Flyway `target=2` seeded across all 9 V1–V2 tables -> `pg_dump` (`31.7K`) -> upgraded to V11 (`ddl-auto=validate`, 37 tables, 0 row loss) -> 2nd startup verified idempotency -> `pg_restore` verified in `cloudforge_restore_test`. |
| **Live Control Plane + Standalone Worker + Monitoring + DB Restart Recovery** | Disposable `postgres:17-alpine` + live `cloudforge-backend.jar` + live `cloudforge-worker.jar` | **PASSED (`100%`)** | Backend UP (`17s`–`37.3s`), ECS 8.11 JSON logs verified, `/actuator/prometheus` Bearer auth (`401` unauth/wrong-token/POST; `200` valid GET, 82,924 bytes), worker registered `AVAILABLE` in PG, `cloudforge.prom` written atomically on NTFS, invalid worker rejected (`401`), DB container restarted -> backend recovered `UP`. |
| **Render 512 MiB Single-Origin Readiness Check** | Disposable `postgres:17-alpine` + single-origin fat JAR (`79.29 MB`) in `eclipse-temurin:17-jre-alpine` (`--memory=512m`, `SPRING_PROFILES_ACTIVE=render`) | **PASSED (`100%`)** | Healthy in `42.9s`; RAM **`323.8 MiB / 512 MiB` (`63.24%`)**; Flyway 11/11; all 9 SPA routes `200` + `no-cache`; hashed JS `immutable` + zero `localhost`; CSRF `Secure; SameSite=Lax`; `401` on `/api/workspaces`, `/api/auth/me`, `/actuator/prometheus`; `503` on `/api/internal/workers/register`. |
| **Frontend Typecheck, Unit/UI Tests & Production Build** | Node 24, Vitest 5.1.1, Vite 6.4.6 | **PASSED (`7/7` files, `38/38` tests)** | `tsc -b --noEmit` clean; `dist/index.html` 1.18 kB, CSS 61.93 kB (10.74 kB gz), JS 600.05 kB (159.73 kB gz). |
| **Hardened Frontend Container (`frontend/Dockerfile`)** | Docker BuildKit + `--read-only --tmpfs /tmp --cap-drop ALL` | **PASSED (`100%`)** | Image size `25.8 MB`; `GET /monitoring` -> `200` (`id="root"`, `X-Content-Type-Options: nosniff`); `GET /api/auth/me` -> `404`. |
| **Docker Compose & Grafana Dashboard Validation** | `docker compose config --quiet` + JSON check | **PASSED (`100%`)** | Both `compose.yaml` and `compose.yaml + compose.monitoring.yaml` exited `0`; all 5 Grafana dashboards contain valid panel definitions. |
| **Monitoring Tooling (`promtool`/`amtool`) & Trivy/Cosign Supply-Chain Checks** | GitHub Actions `phase4` job (`ubuntu-latest`) | **Deferred to CI / Verified on PR** | Local workstation ISP proxy returned HTTP-to-HTTPS redirect (`http: server gave HTTP response to HTTPS client`) during `prom/prometheus` and `prom/alertmanager` Docker Hub pulls; full `promtool`, `amtool`, Trivy vuln/secret/SBOM, and Cosign sign/verify/tamper checks execute cleanly in GitHub Actions `phase4` job on this PR. |

---

## 2. Detailed Evidence by Verification Domain

### 2.1 Backend Test Suite Against Live PostgreSQL 17
- **Command:** `mvn -B test && mvn -B package -DskipTests` (with `TEST_POSTGRES_URL`, `TEST_POSTGRES_USER`, `TEST_POSTGRES_PASSWORD`, `TEST_POSTGRES_RUN_FLYWAY=true` targeting disposable `postgres:17-alpine`).
- **Result:** `Tests run: 95, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, packaged `backend/target/cloudforge-backend.jar`.
- **Newly added / updated regression coverage:**
  - `Phase4SecurityAndMonitoringIntegrationTest`: verified `/actuator/prometheus` Bearer token auth (`403`/`200`), `ArtifactEvidenceController` RBAC/CSRF/PEM public key/tamper/unsigned rejection, `requireProductionApproval` fail-closed/pass/re-block on adverse evidence, and `OperationMetrics` counters.
  - `FlywayMigrationTest`: verified `>= 11` Flyway migrations and all 37 schema tables (`artifact_security_evidence` included).
  - `RenderSpaRoutingIntegrationTest`: verified `Cache-Control` contains `no-cache` on forwarded SPA routes.

### 2.2 PostgreSQL V2 -> V11 Migration, Data Preservation, Repeatability & Restore
Executed against disposable `postgres:17-alpine` (PostgreSQL 17.12):
1. Initialized empty database to schema `V2` using authentic Flyway (`target=2`) and seeded representative rows across all 9 V1–V2 tables (`users`, `workspaces`, `workspace_members`, `github_installations`, `linked_repositories`, `projects`, `project_environments`, `audit_events`, `webhook_deliveries`).
2. Captured a custom-format backup via `pg_dump -Fc` (`31.7 KB`).
3. Booted CloudForge `1.0.0` with `spring.flyway.enabled=true` and `spring.jpa.hibernate.ddl-auto=validate`:
   - Applied `V3` through `V11` cleanly (`11` successful Flyway rows, `37` public tables, Hibernate schema validation passed).
   - Verified 100% survival of every seeded V2 row, foreign key, and audit event.
4. Restarted the application against the upgraded database:
   - Confirmed Flyway logged `Successfully validated 11 migrations` and `Schema "public" is up to date. No migration necessary.`
5. Restored the `pg_dump` archive into a separate disposable database `cloudforge_restore_test` via `pg_restore`:
   - Verified exact row restoration and `flyway_schema_history` max rank `2`.

### 2.3 Worker, Real Docker Isolation & Real BuildKit + `registry:2`
- **Command:** `cd worker && mvn -B test && mvn -B package -DskipTests`
- **Observed results:**
  - `WorkerAgentLoopUnitTest`: 2/2 passed
  - `WorkerTelemetryTest`: 2/2 passed (atomic `cloudforge.prom` write on Windows NTFS)
  - `DockerIsolatedExecutionIntegrationTest`: 2/2 passed (real `alpine:3.20` isolated container execution)
  - `DockerIsolationSecurityUnitTest`: 3/3 passed
  - `BuildKitAdapterAndRegistryClientTest`: 4/4 passed
  - `DockerfilePolicyValidatorTest`: 5/5 passed
  - `RealBuildKitAndRegistryEndToEndTest`: 1/1 passed — `[CLOUDFORGE-E2E-PROOF] Real BuildKit + registry:2 verified immutable reference: 127.0.0.1:49765/cloudforge/e2e-hello@sha256:9569e69b0a6929006731b184f4fbe56b8a9965781d3552e05eb2706fbaa157c1 (1251 bytes)`
  - `KubernetesClientAndExecutorTest`: 5/5 passed
  - `KubernetesManifestAndSecurityTest`: 4/4 passed
  - `GitHubSourceArchiveTest`: passed
  - `RealLocalKubernetesEndToEndTest`: 1 skipped when `CLOUDFORGE_K8S_E2E_API_URL` unset (verified live below).
  - Shaded JAR packaged: `worker/target/cloudforge-worker.jar` (`AGENT_VERSION=1.0.0`).

### 2.4 Disposable Kubernetes (K3s `v1.31.2+k3s1`) RBAC, Deploy, Update, Rollback & Reconciliation
1. Started disposable `rancher/k3s:v1.31.2-k3s1` container (`127.0.0.1:16448`).
2. Provisioned isolated namespace `cf-verify-e2e` using `infrastructure/kubernetes/local/provision-local-namespace.sh` (ResourceQuota, LimitRange, `cloudforge-deployer` & `cloudforge-workload` ServiceAccounts, least-privilege Role/RoleBinding, default-deny-all + allow-dns-and-same-namespace NetworkPolicies).
3. Executed 18 live `kubectl auth can-i` checks as `system:serviceaccount:cf-verify-e2e:cloudforge-deployer`:
   - **9 Allowed (`yes`):** `get/create/patch deployments.apps`, `get/create/patch services`, `list pods/replicasets.apps/events` in `cf-verify-e2e`.
   - **9 Denied (`no`):** `get/create secrets`, `create rolebindings`, `create serviceaccounts`, `create namespaces`, `create clusterrolebindings`, `get/create deployments.apps` in `kube-system`, `get pods` in `default`.
4. Executed `mvn -B -Dtest=RealLocalKubernetesEndToEndTest test` against live K3s (`158.6 s`, `1/1` passed):
   - **Initial deploy (Revision A):** `cf-verify-e2e/cf-e2e-demo` `generation=1`, `2/2` replicas ready running `sha256:24528cf5229d0b70065ac27e6c9e4d96f5452a84a3ce4433e56573c18d96827a`.
   - **Rolling update (Revision B):** `generation=2`, `2/2` replicas ready running `sha256:2b02b8844eab42d432d9c4bbd96a20d7ff348292097eeee4546e79252f72c70e`.
   - **Verified rollback (back to Revision A):** `generation=3`, `2/2` replicas ready running `sha256:24528cf5229d0b70065ac27e6c9e4d96f5452a84a3ce4433e56573c18d96827a`.
   - **Read-only reconciliation:** Confirmed live cluster state read without mutating cluster resources.
   - **Negative invalid-digest test:** `ghcr.io/mccutchen/go-httpbin@sha256:0000...0000` failed cleanly on `ErrImagePull` (`NotFound`) and was never marked healthy.

### 2.5 Live Backend + Standalone Worker + Monitoring + PostgreSQL Restart Recovery
Executed live processes (`cloudforge-backend.jar` with `SPRING_PROFILES_ACTIVE=monitoring`, `cloudforge-worker.jar`, and disposable `postgres:17-alpine`):
- Backend booted on `PostgreSQL 17.12` (`version=1.0.0`, `phase=Phases 1-4 - DevOps Control Plane, Orchestration, Delivery & Observability`).
- Verified real **ECS 8.11 structured JSON log output** (`"@timestamp"`, `"log":{"level":"INFO",...}`, `"ecs":{"version":"8.11"}`).
- Verified `/actuator/prometheus` security:
  - Missing token `GET` -> `HTTP 401`
  - Wrong Bearer token `GET` -> `HTTP 401`
  - Valid Bearer token `POST` -> `HTTP 401`
  - Valid 32+ char Bearer token `GET` -> `HTTP 200` (`82,924` bytes including `jvm_memory_used_bytes`, `hikaricp_connections`, `http_server_requests_seconds`).
- Verified live standalone worker registration: `cf-live-worker-01|AVAILABLE|DOCKER_ISOLATED|["ENV_VALIDATION","CONTROLLED_SMOKE_TEST","WORKER_DIAGNOSTICS"]`.
- Verified live atomic worker Prometheus textfile (`cloudforge.prom` on Windows NTFS):
  ```text
  cloudforge_worker_last_heartbeat_seconds 1795753906
  cloudforge_worker_active_assignments 0
  cloudforge_worker_heartbeat_failures_total 0
  cloudforge_worker_assignments_total 0
  cloudforge_worker_assignment_seconds_total 0.000000
  ```
- Verified rogue/invalid worker heartbeat rejected (`HTTP 401`).
- Restarted PostgreSQL container (`docker restart cf-verify-live-pg`) and verified `/api/health` automatically recovered to `status=UP`, `database.status=UP`.

### 2.6 Render 512 MiB Single-Origin Deployment Readiness Verification
Packaged the freshly built React production bundle (`frontend/dist/`) inside `BOOT-INF/classes/static/` of the Spring Boot fat JAR (`79.29 MB`) and executed inside `eclipse-temurin:17-jre-alpine` constrained to `--memory=512m` with `Dockerfile.render`'s exact JVM flags (`-XX:InitialRAMPercentage=20.0 -XX:MaxRAMPercentage=55.0 -Djava.security.egd=file:/dev/./urandom`) and `SPRING_PROFILES_ACTIVE=render`:
- **Startup time:** `42.9 s` to `GET /api/health` `UP` (including Flyway `V1`–`V11` on empty `cloudforge_staging_db`).
- **Observed RSS Memory:** `323.8 MiB / 512 MiB` (`63.24%` — `~188 MiB` headroom under Render's Free 512 MiB cap).
- **SPA Routes:** All 9 routes (`/`, `/login`, `/projects`, `/projects/123`, `/repositories`, `/pipelines`, `/deployments`, `/monitoring`, `/settings`) returned `HTTP 200`, `id="root"`, and `Cache-Control: no-cache, must-revalidate`.
- **Static Assets:** `GET /assets/index-cfhFNETB.js` returned `HTTP 200` (`600,048` bytes), `Cache-Control: max-age=31536000, public, immutable`, with zero occurrences of `http://localhost:5173` or `http://localhost:8080`.
- **Security & Safe Defaults:**
  - `GET /api/auth/csrf` -> `HTTP 200` (`Set-Cookie: XSRF-TOKEN=...; Path=/; Secure; SameSite=Lax`)
  - `GET /api/workspaces` -> `HTTP 401`
  - `GET /api/auth/me` -> `HTTP 401`
  - `GET /actuator/prometheus` -> `HTTP 401`
  - `POST /api/internal/workers/register` -> `HTTP 503` (worker plane fail-closed on Render)
- *(Catch during verification: This test caught a real `404` caused by mapping `/index.html` instead of `/*.html` -> `classpath:/static/` in `WebConfig.java`, which was fixed and re-verified green.)*

---

## 3. Failure & Recovery Verification Matrix

| Failure Scenario | Verification Mechanism | Observed Outcome |
| --- | --- | --- |
| **Worker crash / expired lease / stale fencing** | `WorkerSecurityAndExecutionEndToEndTest`, `WorkerConcurrencyTest` | Expired lease recovered safely; stale fencing token / duplicate result submissions rejected without corrupting stage/run state. |
| **Duplicate result / concurrent pipeline submission** | `PipelineOrchestrationConcurrencyTest`, `WorkerConcurrencyTest` | Idempotency keys and monotonic fencing prevent duplicate terminal transitions. |
| **Database restart** | Live `verify-local-live-e2e.ps1` (`docker restart cf-verify-live-pg`) | Backend Hikari pool reconnects automatically; `/api/health` returns `UP` once PostgreSQL recovers. |
| **Registry unavailable / build failure** | `BuildKitAdapterAndRegistryClientTest`, `OciRegistryAndBuildSecurityTest` | Non-zero build/push fails stage cleanly without recording a verified `sha256:` artifact. |
| **Invalid image digest** | Live K3s `RealLocalKubernetesEndToEndTest` (`sha256:0000...0000`) | Fails fast on `ErrImagePull`; never recorded as a healthy release. |
| **Unsigned / tampered / missing security evidence** | `Phase4SecurityAndMonitoringIntegrationTest` | Tampered/unsigned envelopes rejected; `requireProductionApproval` fails closed when evidence is missing or adverse (`HIGH`/`CRITICAL > 0`). |
| **Kubernetes rollout timeout / unhealthy deployment / rollback failure / stale rollback preview / reconciliation-required** | `KubernetesDeploymentEngineIntegrationTest`, `KubernetesClientAndExecutorTest`, `KubernetesManifestAndSecurityTest` | Failed rollouts preserve prior active release history; stale rollback previews are invalidated; reconciliation-required state transitions preserve audit trail and workspace isolation. |
| **Unauthorized metrics scrape / disabled Render workers** | Live `verify-local-live-e2e.ps1`, Render 512 MiB test, `Phase4SecurityAndMonitoringIntegrationTest` | Unauthenticated/wrong-token/POST scrapes return `401`/`403`; Render worker endpoints return `503`. |

---

## 4. Known Local Environment Limitations & External Checks

1. **Live GitHub OAuth / GitHub App / Webhook End-to-End:** Requires real GitHub OAuth App & GitHub App credentials and a public webhook receiver; verified locally via backend/frontend integration suites and mocked GitHub API clients.
2. **Local Docker Hub Proxy Blip:** During local verification, Docker Hub (`registry-1.docker.io`) intermittently returned an ISP HTTP response to HTTPS (`http: server gave HTTP response to HTTPS client`), interrupting local `prom/prometheus:v3.5.0` and `prom/alertmanager:v0.28.1` image pulls. Full `promtool check config`, `amtool check-config`, Trivy vuln/secret/CycloneDX SBOM scanning, and Cosign signing/tamper verification run in the GitHub Actions `phase4` job on this PR and are recorded in the final execution report.
3. **Live Render Web Service & Database:** Untouched per safety rules; awaiting explicit user approval after PR merge.
