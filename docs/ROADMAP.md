# CloudForge Development Roadmap

This roadmap clearly distinguishes **Implemented (Phases 1–2)** functionality from **Planned (Phases 3–4)** capabilities.

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

## Phase 3 — Isolated Build Workers, Container Deployments & Kubernetes (**Planned**)

- [ ] **GitHub Actions Synchronization:** Ingest and display real CI workflow statuses and commit checks linked to CloudForge projects.
- [ ] **Isolated Container Build Workers:** Execute repository container builds strictly inside ephemeral, rootless workers with enforced CPU, memory, disk, timeout, and network egress limits (never on the control-plane host).
- [ ] **Encrypted Secret Storage:** Introduce KMS / External Secrets integration for project environment secrets (deferred from Phase 2).
- [ ] **Docker Image Deployment:** Push immutable, content-addressed OCI digests to a configured container registry.
- [ ] **Kubernetes Orchestration (`infrastructure/kubernetes/`):** Deploy projects to controlled local (kind/k3d) and remote Kubernetes namespaces using least-privilege service accounts, readiness/liveness probes, and `NetworkPolicy` isolation.
- [ ] **Deployment History & Rollback:** Persist every release revision (commit SHA, image digest, manifest checksum, actor, outcome) and provide one-click rollback to previously verified healthy releases.

---

## Phase 4 — Observability & DevSecOps Automation (**Planned**)

- [ ] **Prometheus Integration (`infrastructure/monitoring/`):** Expose `/actuator/prometheus` via Micrometer and scrape API, PostgreSQL, build worker, and Kubernetes cluster metrics.
- [ ] **Grafana Dashboards & Alerting:** Ship version-controlled Grafana dashboards and alerting rules for latency, error rates, database pool exhaustion, and failed rollouts.
- [ ] **DevSecOps Policy Gates:** Automate secret scanning, Maven/npm dependency CVE checks, container image scanning (Trivy), SBOM generation (Syft), and image signing (Cosign) prior to Kubernetes promotion.
- [ ] **Production Frontend Container & Ingress:** Add hardened NGINX/static frontend image and TLS ingress templates under `infrastructure/docker/` and `infrastructure/kubernetes/`.
