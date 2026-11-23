# CloudForge Phase 3 Readiness Report

> **Document Status:** Baseline Preparation Complete — **Phase 3 Execution Features Not Yet Implemented**  
> **Prepared On:** 2026-11-23  
> **Preparation Branch:** `chore/phase3-baseline-preparation` (branched from `origin/main` at `7c6ee4e`)  
> **Scope Notice:** All Phase 1 and Phase 2 capabilities below are **Implemented** and verified against the current repository baseline. All Phase 3 capabilities (Sections 3.1–3.7) are explicitly **Planned** and are not yet implemented.

---

## 1. Current Repository Baseline

CloudForge is a self-service cloud deployment and DevOps automation platform organized as a full-stack monorepo (`frontend/`, `backend/`, `infrastructure/`, `docs/`, `.github/workflows/`).

| Component | Current Baseline | Status |
| --- | --- | --- |
| **Repository** | `https://github.com/Itzx-kush/CloudForge` | Active |
| **Default Branch (`origin/main`)** | `7c6ee4e` (Merge PR #4 from `deploy/render-phase2-staging-20261123`) | Baseline prior to Hikari sync |
| **Render Staging Branch** | `origin/deploy/render-phase2-staging-20261123` (`39375f9`) | 1 commit ahead of `origin/main` |
| **Backend** | Java 17 (Temurin `17.0.15`), Spring Boot `3.4.5`, Maven `3.9.16`, Spring Security 6, OAuth2 Client, Spring Data JPA, Flyway 10, BouncyCastle `1.79`, Nimbus JOSE+JWT | **Implemented (Phases 1–2)** |
| **Frontend** | React `19.0.0`, TypeScript `5.7.3`, Vite `6.4.6`, React Router `7.2.0`, Vitest `5.1.1`, React Testing Library `16.2.0` (verified on Node `v24.19.0` / npm `11.17.0`) | **Implemented (Phases 1–2)** |
| **Database & Schema** | PostgreSQL 17 (`postgres:17-alpine`), Flyway migrations `V1__phase2_core_schema.sql` & `V2__webhook_deliveries.sql` (9 domain tables) | **Implemented (Phases 1–2)** |
| **Containerization & Hosting** | Local Docker Compose (`compose.yaml` + `backend/Dockerfile`), Render single-origin staging container (`Dockerfile.render` + `application-render.yml` + `SpaPageController`) | **Implemented (Phases 1–2)** |
| **CI Pipeline** | `.github/workflows/ci.yml` (Backend Java 17 `mvn -B test` & `package`, Frontend Node 24 `npm ci`, `typecheck`, `test`, `build`) | **Implemented (Phase 1+)** |
| **CI/CD Pipelines, Build Workers, Secrets KMS, Registry, Kubernetes** | Placeholder architecture documentation & planned UI views (`DeploymentsPage`, `PipelinesPage`, `infrastructure/`) | **Planned (Phase 3)** |
| **Prometheus, Grafana & DevSecOps Policy Gates** | Placeholder architecture documentation & live `/api/health` + `/actuator` probes only | **Planned (Phase 4)** |

---

## 2. Completed Phase 1 Functionality (**Implemented**)

- **Monorepo & Developer Tooling:** Structured `frontend/`, `backend/`, `infrastructure/`, `docs/`, and `.github/workflows/ci.yml` layout with `.env.example` and `.gitignore`.
- **Backend Core API (`com.cloudforge.backend`):**
  - Modular Spring Boot 3.4 architecture (`config`, `controller`, `dto`, `entity`, `exception`, `repository`, `service`).
  - Real JDBC connectivity probe (`DatabaseProbeRepository` executing `SELECT 1`) exposed via `GET /api/health`, returning `200 OK` (`UP`) when PostgreSQL is reachable and `503 Service Unavailable` (`DEGRADED`) when unavailable.
  - Spring Boot Actuator endpoints (`/actuator/health`, `/actuator/info`) with structured UTC logging (`logback-spring.xml`) and `X-Request-Id` correlation via `RequestCorrelationFilter` (SLF4J MDC).
  - Global structured error responses (`GlobalExceptionHandler` / `ApiErrorResponse`) with stack traces and internal error messages suppressed (`server.error.include-stacktrace: never`, `include-message: never`).
- **Frontend Developer Console:**
  - React 19 + TypeScript + Vite Obsidian dark design system with reusable components (`Layout`, `Sidebar`, `Header`, `PageContainer`, `DashboardCard`, `DataTable`, `StatusBadge`, `LoadingState`, `ErrorState`, `Modal`) and live `/api/health` polling hook (`useBackendHealth`).
  - Vite development proxy (`frontend/vite.config.ts`) forwarding `/api`, `/actuator`, `/oauth2`, and `/login/oauth2` with `xfwd: true` for same-origin local sessions.
- **Local Infrastructure:**
  - `compose.yaml` running PostgreSQL 17 with persistent named volume `cloudforge_postgres_data`, `pg_isready` health check, localhost-only port bindings (`127.0.0.1`), and multi-stage non-root `backend/Dockerfile`.

---

## 3. Completed Phase 2 Functionality (**Implemented**)

- **GitHub OAuth 2.0 Authentication & Session Security:**
  - Spring Security authorization-code login (`GitHubOAuthRegistrationConfig`, `CloudForgeOAuth2UserService`) scoped strictly to `read:user` and `user:email`.
  - Transient OAuth token handling: access tokens are only used server-side during login to retrieve a verified primary email if absent, and are never persisted in PostgreSQL, stored in `CloudForgeUserPrincipal`, logged, or returned to browser JavaScript.
  - HTTP-only server-side session cookie (`CLOUDFORGE_SESSION`), `sessionFixation().changeSessionId()`, environment-driven `Secure` / `SameSite` settings, open-redirect allowlist validation (`RedirectOriginValidator`), SPA CSRF protection (`CookieCsrfTokenRepository`, `SpaCsrfTokenRequestHandler`, `CsrfCookieFilter`), graceful unconfigured guard (`OAuthConfigGuardFilter`), `GET /api/auth/config`, `GET /api/auth/csrf`, `GET /api/auth/me`, and `POST /api/auth/logout`.
- **Flyway-Managed PostgreSQL Schema (`V1` & `V2`):**
  - `V1__phase2_core_schema.sql`: `users`, `workspaces`, `workspace_members`, `github_installations`, `linked_repositories`, `projects`, `project_environments`, `audit_events` with UUID keys, check constraints, unique constraints, and indexes.
  - `V2__webhook_deliveries.sql`: `webhook_deliveries` with unique `delivery_id` and SHA-256 payload digest for idempotent webhook processing without persisting raw payload bodies.
  - Typed JPA Criteria search queries (`ProjectRepository`, `LinkedRepositoryRepository`) avoiding PostgreSQL `lower(bytea)` errors on nullable search/filter parameters.
- **Multi-Workspace Tenancy & RBAC:**
  - Automatic personal workspace provisioning on first OAuth login (`UserAccountService`).
  - Backend-enforced `OWNER`, `DEVELOPER`, and `VIEWER` roles (`WorkspaceAuthorizationService`), workspace/member management (`WorkspaceController` / `WorkspaceService`), last-owner protection, cross-workspace isolation, and `OWNER`-restricted immutable audit log queries (`AuditEventController` / `AuditQueryService`).
- **Least-Privilege GitHub App Integration & Verified Webhooks:**
  - RS256 GitHub App JWT signing (`GitHubAppTokenService`, supporting PKCS#1 and PKCS#8 PEM via BouncyCastle + Nimbus JOSE+JWT) and in-memory caching/eviction of short-lived installation access tokens requested strictly with `permissions: { metadata: "read" }` (`RestClientGitHubApiClient`).
  - Workspace-scoped installation URL creation (`OWNER`-only, 32-byte CSPRNG single-use session state, 15-minute TTL), callback verification with cross-workspace conflict and personal account ownership checks (`GitHubIntegrationService`), repository discovery/sync, and clean handling of revoked (`404`), suspended (`403`), and rate-limited (`429`) installations.
  - `POST /api/webhooks/github` (`GitHubWebhookController`, `GitHubWebhookService`, `WebhookSignatureVerifier`) performing constant-time HMAC-SHA256 verification over raw request bytes, delivery deduplication, lifecycle synchronization for `ping`, `installation`, `installation_repositories`, and `repository` events, and safe ignoring (`202 Accepted`) of unsupported events such as `push`.
- **Projects, Persistent Environments & Full Phase 2 UI:**
  - Full PostgreSQL CRUD for projects (`ProjectController` / `ProjectService`), authorized repository linking, and `Development` / `Staging` / `Production` non-secret environment configurations (`ProjectEnvironmentService`) actively blocking secret-like keys and credential token/PEM patterns.
  - Authenticated React console (`LoginPage`, `ProtectedRoute`, `AuthContext`, `DashboardPage`, `ProjectsPage`, `ProjectDetailPage`, `RepositoriesPage`, `SettingsPage`, `MonitoringPage`) plus clearly labeled planned blueprint views (`DeploymentsPage`, `PipelinesPage`).
- **Render Single-Origin Staging Support:**
  - `Dockerfile.render` (multi-stage Node 22 + Maven/Temurin 17 + non-root JRE 17 runtime with `infrastructure/docker/maven-settings-render.xml`), `SpaPageController` forwarding public SPA shell routes while preserving backend API authentication, `RenderSpaRoutingIntegrationTest`, `application-render.yml`, and `docs/RENDER_STAGING.md`.

---

## 4. Repository Synchronization Findings

Fetching all remotes (`git fetch --all --prune`) and comparing `origin/main` (`7c6ee4e`) against `origin/deploy/render-phase2-staging-20261123` (`39375f9`) revealed:

1. **Local Checkout Lag:** The local `main` branch (`95955cf`) had not yet pulled Merge PR #4 (`7c6ee4e`), which merged the Render staging setup (`Dockerfile.render`, `SpaPageController.java`, `SecurityConfig.java` public SPA shell routes, `application-render.yml`, `RenderSpaRoutingIntegrationTest.java`, `docs/RENDER_STAGING.md`, `infrastructure/docker/maven-settings-render.xml`) into `origin/main`.
2. **Post-Merge Commit Missing from `origin/main`:** Merge base between `origin/main` and `origin/deploy/render-phase2-staging-20261123` is `8d52e61`. After PR #4 was merged, commit `39375f9` (`fix(render): allow slower managed PostgreSQL connection startup`) was pushed to `origin/deploy/render-phase2-staging-20261123` and had not been synchronized into `origin/main`.
3. **Exact File Difference (`1 file changed, 8 insertions(+)`):** `backend/src/main/resources/application-render.yml` on `origin/main` lacked the Render-specific HikariCP pool configuration (`connection-timeout: 30000`, `validation-timeout: 5000`, `initialization-fail-timeout: 30000`, `maximum-pool-size: 5`, `minimum-idle: 1`), causing `render` profile deployments built from `main` to inherit the 3-second local-dev timeout from `application.yml`.
4. **Synchronization Applied:** Created `chore/phase3-baseline-preparation` from `origin/main` (`7c6ee4e`) and synchronized the exact 8-line HikariCP block into `backend/src/main/resources/application-render.yml`. Subsequent `git diff origin/deploy/render-phase2-staging-20261123` confirmed zero remaining diff across all tracked source files.
5. **Render Profile Verification:** Verified that `application-render.yml` binds `${PORT:10000}`, reads PostgreSQL settings solely from environment variables (`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`) with zero hardcoded credentials, preserves localhost `application.yml` defaults, and retains environment-driven OAuth/GitHub App callbacks, webhook HMAC-SHA256 verification, and `CLOUDFORGE_COOKIE_SECURE` cookie settings.

---

## 5. Backend Test Results

Executed from `backend/` on Java `17.0.15` (Eclipse Adoptium Temurin) and Apache Maven `3.9.16` after synchronizing `application-render.yml`:

### Commands & Outcomes

| Command | Outcome | Duration | Details |
| --- | --- | --- | --- |
| `mvn test` | **BUILD SUCCESS** | `59.577 s` | **34** total, **31** passed, **0** failed, **0** errors, **3** skipped |
| `mvn package -DskipTests` | **BUILD SUCCESS** | `15.521 s` | Repackaged executable Spring Boot JAR `target/cloudforge-backend.jar` (`73,554,887` bytes) |

### Test Suite Breakdown

| Test Class | Run | Passed | Failed | Skipped | Notes |
| --- | --- | --- | --- | --- | --- |
| `CloudForgeBackendApplicationTests` | 3 | 3 | 0 | 0 | Public `/api/health`, `/actuator/health`, structured 401 |
| `HealthControllerTest` | 3 | 3 | 0 | 0 | WebMvc health status mapping |
| `GitHubAppTokenServiceTest` | 1 | 1 | 0 | 0 | Ephemeral RSA PEM parsing, RS256 JWT signing, in-memory token cache/eviction |
| `AuthAndSecurityIntegrationTest` | 6 | 6 | 0 | 0 | Public `/api/auth/config`, 401 guard, OAuth upsert, `/api/auth/me`, CSRF + logout invalidation, 404 |
| `GitHubAppAndRepositoriesIntegrationTest` | 4 | 4 | 0 | 0 | Mocked `GitHubApiClient`: RBAC, state/stateless callback, single-use replay protection, ownership checks, revocation, rate limits |
| `GitHubWebhookIntegrationTest` | 3 | 3 | 0 | 0 | Real HMAC-SHA256 signatures, invalid/missing rejection, delivery deduplication, lifecycle events, `push` ignored (`202`) |
| `PostgresSearchQueryRegressionTest` | 3 | 0 | 0 | **3** | Conditionally skipped by `PostgresAvailableCondition` — local PostgreSQL on `127.0.0.1:5432` was not running (Docker daemon stopped on host) |
| `RenderSpaRoutingIntegrationTest` | 2 | 2 | 0 | 0 | Public SPA route forwarding + protected API enforcement |
| `WorkspaceRbacAndProjectsIntegrationTest` | 2 | 2 | 0 | 0 | Role matrix (`OWNER`/`DEVELOPER`/`VIEWER`/outsider), cross-workspace isolation, plaintext secret rejection, last-owner guard |
| `FlywayMigrationTest` | 1 | 1 | 0 | 0 | Applies `V1` & `V2` on H2 PG-mode and validates all 9 tables |
| `DatabaseProbeRepositoryTest` | 2 | 2 | 0 | 0 | Healthy and failing JDBC probe paths |
| `RedirectOriginValidatorTest` | 2 | 2 | 0 | 0 | Safe redirects vs. open-redirect / `javascript:` blocking |
| `HealthServiceTest` | 2 | 2 | 0 | 0 | `UP` vs. `DEGRADED` status mapping |
| **Total** | **34** | **31** | **0** | **3** | **0 failures, 0 errors** |

### Observed Backend Warnings

- Flyway (`10.x` via Spring Boot 3.4.5): `Flyway upgrade recommended: H2 2.3.232 is newer than this version of Flyway and support has not been tested. The latest supported version of H2 is 2.2.224.` (test-profile H2 only).
- Hibernate Dialect (test profile): `HHH000511: The -9999.0.0 version for [org.hibernate.dialect.H2Dialect] is no longer supported...` (triggered by H2 metadata in `test` profile; does not occur against PostgreSQL 17).
- JVM test runner: `OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended` (ByteBuddy/Mockito test agent).
- Maven Surefire summary warning due to the 3 conditionally skipped PostgreSQL regression tests.

---

## 6. Frontend Test Results

Executed from `frontend/` on Node `v24.19.0` and npm `11.17.0`:

| Command | Outcome | Details |
| --- | --- | --- |
| `npm ci` | **PASS** (exit `0`) | Added 150 packages, audited 151 packages in 23s, **0 vulnerabilities** (`npm warn` noted deprecated transitive `whatwg-encoding@3.1.1` and npm 11 `allow-scripts` notice for `esbuild@0.25.12`) |
| `npm run typecheck` | **PASS** (exit `0`) | `tsc -b --noEmit` completed with 0 TypeScript errors |
| `npm test` | **PASS** (exit `0`) | Vitest `5.1.1`: **1 test file passed**, **9/9 tests passed** (`src/test/App.test.tsx`, 13.89s) |
| `npm run build` | **PASS** (exit `0`) | `tsc -b && vite build` (`vite 6.4.6`): transformed 1638 modules in 4.00s → `dist/index.html` (`0.97 kB`), `dist/assets/index-*.css` (`24.74 kB`), `dist/assets/index-*.js` (`382.79 kB`) |

---

## 7. Docker Verification Results

Inspected `compose.yaml`, `Dockerfile.render`, `backend/Dockerfile`, `backend/.dockerignore`, and `infrastructure/docker/maven-settings-render.xml` with Docker CLI `29.8.0` and Docker Compose `v5.5.1`:

- **Compose Configuration Validation:** `docker compose config --quiet` exited with code **`0`** (valid YAML schema, service dependencies, health checks, networks, volumes, and environment variable interpolation).
- **Local Docker Daemon Status:** `docker compose ps -a` / `docker ps` could not connect to the local Docker Desktop engine (`npipe:////./pipe/dockerDesktopLinuxEngine` unavailable — Docker Desktop was stopped on the host). No containers were started, modified, or deleted during this verification, and existing volumes were untouched.
- **Dockerfile Review:**
  - `backend/Dockerfile`: Multi-stage Maven (`maven:3.9.9-eclipse-temurin-17-alpine`) → non-root JRE (`eclipse-temurin:17-jre-alpine`, user `cloudforge`), `EXPOSE 8080`, `HEALTHCHECK` probing `http://127.0.0.1:8080/api/health`. Compatible with `compose.yaml`.
  - `Dockerfile.render`: Multi-stage `node:22-alpine` → `maven:3.9.9-eclipse-temurin-17-alpine` (with Google Maven Central mirror settings) → non-root JRE `eclipse-temurin:17-jre-alpine` (`cloudforge`), copies Vite `dist/` into `src/main/resources/static/`, `EXPOSE 10000`. Compatible with Render single-origin HTTPS architecture.
  - **Gap Noted:** `backend/.dockerignore` exists for `compose.yaml` backend builds, but there is **no root `.dockerignore`** for root-context `Dockerfile.render` builds (see Security Finding #2).

---

## 8. Security Review Findings

A focused source-code security review was performed across authentication, GitHub App integration, webhooks, CSRF/session handling, workspace RBAC, credential handling, and Git/Docker hygiene. No existing security controls were weakened or removed.

### Verified Security Controls (Baseline Passing)

- GitHub OAuth requests least-privilege scopes (`read:user`, `user:email`) and never persists or returns OAuth access tokens to browser JavaScript.
- Open-redirect protection (`RedirectOriginValidator`) validates redirect destinations against configured origins.
- Session cookie (`CLOUDFORGE_SESSION`) enforces `HttpOnly`, cookie-only tracking, `changeSessionId()` session fixation protection, and environment-driven `Secure` flag.
- SPA CSRF (`XSRF-TOKEN` / `X-XSRF-TOKEN`) protects all state-changing endpoints; only `POST /api/webhooks/github` (HMAC-SHA256 authenticated) and `/api/health` (read-only) are exempted.
- Webhook verification (`WebhookSignatureVerifier`) computes `HmacSHA256` over raw request bytes with constant-time `MessageDigest.isEqual`, fails closed when unconfigured (`503`), and rejects invalid signatures (`401`).
- GitHub App installation tokens are strictly scoped to `metadata: read`, cached in memory only, and evicted on errors/revocation/suspension.
- Backend enforces `OWNER`/`DEVELOPER`/`VIEWER` RBAC, last-owner protection, cross-workspace repository binding prevention, and `OWNER`-only audit log access.
- `.gitignore` excludes `.env`, `.env.*` (except `.env.example`), `*.pem`, `*.key`, `*.p12`, `*.jks`, `*.keystore`, `target/`, `node_modules/`, and `dist/` (verified with `git check-ignore -v`).

### Categorized Findings & Pre-Phase 3 Recommendations

| ID | Severity | Component | Finding | Recommended Remediation |
| --- | --- | --- | --- | --- |
| **SEC-01** | **Medium** | `GitHubIntegrationService.java` (`L157-L166`, `L458-L472`) | **Optional `state` fallback combined with missing Organization ownership verification:** `consumeAndValidateState()` allows `state == null` when a 15-minute pending session exists, and `completeInstallationCallback()` skips GitHub account ownership verification when `accountType` is `Organization`. A workspace `OWNER` with an active pending install session could be tricked via a top-level GET navigation into linking an unattached Organization installation ID without verifying GitHub org admin status. | Before Phase 3 elevates GitHub App permissions beyond `metadata: read`, verify GitHub Organization admin/installation authorization (e.g., GitHub App user-to-server `/user/installations` check) and require `state` verification on initial `setup_action=install`. |
| **SEC-02** | **Medium** | `Dockerfile.render` & repository root | **Missing root `.dockerignore`:** `Dockerfile.render` uses the repository root as its build context (`COPY frontend/ ./`). Locally, without a root `.dockerignore`, Docker sends `.git/`, `.env`, `frontend/node_modules/`, `frontend/dist/`, and `backend/target/` to the daemon, and `COPY frontend/ ./` could include local `frontend/.env*` or host `node_modules` in the build stage. *(Mitigated on Render because Render builds from a clean Git clone.)* | Add a root `.dockerignore` excluding `.git`, `.env`, `.env.*`, `*.pem`, `*.key`, `frontend/node_modules`, `frontend/dist`, and `backend/target` before Phase 3 container build tooling is introduced. |
| **SEC-03** | **Medium** | `ProjectEnvironmentService.java` (`L162-L168`) | **Authorization-after-lookup oracle in `updateEnvironment()`:** Unlike `listEnvironments`, `createEnvironment`, and `deleteEnvironment`, `updateEnvironment` calls `ensureProjectInWorkspace` and `findByIdAndProjectId` *before* calling `authorizationService.requireEnvironmentWriteAccess(...)`. An authenticated outsider receives `404` for non-existent UUIDs vs. `403` for valid `(workspaceId, projectId, environmentId)` tuples. Mutation is still blocked (`403`). | Enforce `authorizationService.requireMembership(workspaceId, user.getId())` (or `requireDeveloperOrAbove`) prior to loading the project/environment entities, then check `requireEnvironmentWriteAccess` on the resolved `EnvironmentType`. |
| **SEC-04** | **Low** | `GitHubWebhookService.java` (`L108-L154`) | **Concurrent webhook deduplication race inside `@Transactional`:** `saveDelivery()` executes *after* side effects and catches `DataIntegrityViolationException` inside an active transaction. Under simultaneous duplicate deliveries on PostgreSQL, catching a unique-constraint failure inside a transaction aborts the PostgreSQL transaction and can fail commit rather than returning `200 duplicate`, and allows duplicate side effects to run before insert. | Claim/insert the `webhook_deliveries` row *first* (or use atomic `INSERT ... ON CONFLICT DO NOTHING`) before triggering Phase 3.1/3.2 pipeline side effects. |
| **SEC-05** | **Low** | `GitHubWebhookController.java` / `SecurityConfig.java` | **No explicit payload cap or rate limit on `POST /api/webhooks/github`:** Raw webhook payloads are read into `byte[]` prior to HMAC validation without an explicit per-endpoint body size limit or rate limiter. | Enforce an explicit max payload size (e.g., 1–5 MB) and rate limiting before Phase 3 adds high-frequency `workflow_run`/`check_suite`/`push` webhooks. |
| **SEC-06** | **Low** | `SecurityConfig.java` (`L58`, `L72-L77`), `AuthController.java` (`L165`, `L172`) | **Hardcoded `SameSite=Lax` on CSRF/logout cookies and missing CSP header:** CSRF and logout cookies hardcode `"Lax"` instead of reading `CLOUDFORGE_COOKIE_SAME_SITE`; no explicit `Content-Security-Policy` header is configured for the served SPA. | Align CSRF/logout `SameSite` with configuration and add a strict `Content-Security-Policy` header in `SecurityConfig` before rendering live build/deployment logs in Phase 3. |
| **SEC-07** | **Low** | `WorkspaceService.java` (`L145-L149`, `L268`) | **Registered-user enumeration & email exposure on `addMember`:** Any user can create a workspace as `OWNER`, probe registered GitHub usernames via `addMember`, and read `user.email` via `listMembers` without an invite-acceptance handshake. | Introduce pending workspace invitations with user consent before expanding beyond private/team staging. |
| **SEC-08** | **Low** | `ProjectEnvironmentService.java` (`L43-L57`, `L293-L305`) | **Heuristic plaintext secret blocking only:** Key-fragment and prefix checks reject common secrets but cannot catch arbitrary unprefixed credentials or connection strings under benign variable names; `config_variables_json` is stored unencrypted. | Explicitly addressed by planned Phase 3.4 (Encrypted Secret Management) with envelope encryption/KMS and write-only secret APIs. |
| **SEC-09** | **Low** | `CloudForgeOAuth2UserService.java` (`L32`), `RedirectOriginValidator.java` (`L41-L49`) | **Missing HTTP client timeouts & URI `userInfo` check:** `CloudForgeOAuth2UserService`'s `RestClient` for `/user/emails` omits connect/read timeouts; `RedirectOriginValidator.extractOrigin()` does not explicitly reject URLs containing `uri.getUserInfo() != null`. | Add bounded connect/read timeouts on the OAuth email `RestClient` and reject non-null `uri.getUserInfo()` in `RedirectOriginValidator`. |

---

## 9. Known Technical Limitations

1. **PostgreSQL Regression Tests Require Live PostgreSQL:** `PostgresSearchQueryRegressionTest` (3 tests) is skipped whenever PostgreSQL 17 is not running locally (`127.0.0.1:5432`) and does not run in GitHub Actions CI (`ci.yml` runs `mvn -B test` without a PostgreSQL service container). Adding a PostgreSQL 17 CI service container (or Testcontainers) is strongly recommended as Phase 3 migrations grow.
2. **In-Memory Session & Installation Token State:** Tomcat HTTP sessions (`CLOUDFORGE_SESSION`) and GitHub App installation tokens (`GitHubAppTokenService`) are stored strictly in JVM memory. Horizontal scaling or container restarts clear active user sessions and require re-authentication.
3. **Render Free Tier Constraints:** Per `docs/RENDER_STAGING.md`, the Render staging free PostgreSQL instance expires on December 23, 2026 and free web services spin down on idle (with slower cold starts). Staging must not hold irreplaceable data or execute untrusted build workloads on the control-plane web dyno.
4. **Node Version Minor Skew:** `.github/workflows/ci.yml` builds with Node 24 while `Dockerfile.render` builds with `node:22-alpine`.
5. **No Live External Verification in Baseline Run:** Live GitHub OAuth sign-in, live GitHub App installation against `github.com`, live Render staging HTTP probes, and Docker image builds were intentionally **not** executed in this baseline preparation step (per safety rules: no live infrastructure mutation and local Docker daemon stopped).
6. **Phase 3 & Phase 4 Capabilities Not Yet Implemented:** GitHub Actions ingestion, pipeline orchestration, isolated container build workers, encrypted secrets management, OCI container registry publishing, Kubernetes deployment execution, deployment rollback, Prometheus metrics scraping, Grafana dashboards, and DevSecOps scanners remain **Planned**.

---

## 10. Recommended Implementation Order for Phase 3 (**Planned**)

Phase 3 must be implemented incrementally on top of the verified Phase 1–2 baseline, preserving all existing authentication, RBAC, webhook, and database guarantees. **None of the items below are implemented yet.**

### 3.1 — GitHub Actions Integration (**Planned**)
- **Goal:** Read-only ingestion and visibility of real GitHub Actions workflows, runs, jobs, and commit check statuses for linked workspace repositories.
- **Planned Scope:** Flyway `V3` tables for workflow run/check telemetry, expanded GitHub App read permissions (`actions: read`, `checks: read`), HMAC-verified `workflow_run` / `check_run` webhook ingestion (with atomic delivery claim fixing SEC-04), backend REST APIs under `/api/workspaces/{workspaceId}/projects/{projectId}/pipelines`, and live pipeline telemetry in `PipelinesPage`.

### 3.2 — Pipeline Orchestration Engine (**Planned**)
- **Goal:** Persistent state machine coordinating build, scan, publish, and deploy stages for CloudForge projects with workspace RBAC and audit logging.
- **Planned Scope:** Persistent pipeline definitions, execution runs, step state transitions (`QUEUED` → `RUNNING` → `SUCCEEDED` / `FAILED` / `CANCELLED`), concurrency locks per project/environment, cancellation/retry semantics, and durable job queueing decoupled from HTTP request threads.

### 3.3 — Isolated Build Workers (**Planned**)
- **Goal:** Execute untrusted repository container builds exclusively inside ephemeral, rootless sandboxed workers — never on the Spring Boot control-plane host.
- **Planned Scope:** Ephemeral rootless build engine (BuildKit / Kaniko / isolated worker pods or containers), strict CPU, memory, ephemeral disk, wall-clock timeout, and egress network limits, short-lived scoped repository clone tokens (`contents: read`), and structured build log streaming/redaction.

### 3.4 — Encrypted Secret Management (**Planned**)
- **Goal:** Replace Phase 2's non-secret-only environment boundary with proper encrypted secret storage per project environment.
- **Planned Scope:** Envelope encryption (DEK/KEK via KMS or managed master key), write-only APIs (secret values never returned after write, masked in UI and redacted in build logs/audit events), strict `OWNER` / environment RBAC, rotation metadata, and secure runtime injection into build workers and Kubernetes workloads.

### 3.5 — OCI Container Registry Integration (**Planned**)
- **Goal:** Publish and verify immutable, content-addressed OCI container images produced by isolated build workers.
- **Planned Scope:** Registry configuration (e.g., GHCR / private OCI registry) with encrypted credentials, pushing tags + capturing immutable `sha256` image digests, persisting artifact provenance (commit SHA, build run ID, digest, timestamp) in PostgreSQL, and enforcing digest-pinned references downstream.

### 3.6 — Kubernetes Deployment Engine (**Planned**)
- **Goal:** Declaratively deploy immutable container images into isolated target Kubernetes namespaces.
- **Planned Scope:** Target cluster/namespace registration (`infrastructure/kubernetes/` manifests & RBAC templates), least-privilege `ServiceAccount` / `Role` / `NetworkPolicy` isolation, Kubernetes Java Client integration with readiness/liveness probes, resource requests/limits, progressive rollout status tracking, and verification against local clusters (`kind` / `k3d`) prior to remote clusters.

### 3.7 — Deployment History and Rollback (**Planned**)
- **Goal:** Maintain an immutable release ledger and enable safe, auditable rollback to previously verified healthy releases.
- **Planned Scope:** PostgreSQL release revision ledger recording commit SHA, immutable image digest, environment config/secret version hash, manifest checksum, actor, and rollout outcome; live release history in `DeploymentsPage`; and `OWNER`/authorized one-click rollback re-applying a prior verified digest with full audit trail coverage.
