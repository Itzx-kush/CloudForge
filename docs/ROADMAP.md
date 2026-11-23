# CloudForge Development Roadmap

This roadmap clearly distinguishes **Implemented (Phase 1)** functionality from **Planned (Phases 2–4)** capabilities.

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
- [x] Automated backend (JUnit 5 / MockMvc / SpringBootTest) and frontend (Vitest / React Testing Library) test suites plus GitHub Actions Phase 1 CI workflow.
- [x] Comprehensive `.gitignore`, `.env.example`, `README.md`, and architectural documentation.

---

## Phase 2 — Identity, GitHub Integration & Persistent Workspaces (**Planned**)

- [ ] **Database Migrations:** Introduce Flyway migrations under `backend/src/main/resources/db/migration/` building on `BaseAuditableEntity`.
- [ ] **Authentication & RBAC:** Implement GitHub OAuth login via Spring Security and workspace role enforcement (`OWNER`, `DEVELOPER`, `VIEWER`).
- [ ] **GitHub App Integration:** Connect repositories using least-privilege GitHub App installation tokens and HMAC-SHA256 verified webhooks.
- [ ] **Projects & Repositories APIs:** Full CRUD APIs for projects, environment configurations, and linked repositories, replacing Phase 1 demonstration states in the UI.
- [ ] **Audit Trail:** Record user actions, repository bindings, and configuration changes in PostgreSQL.

---

## Phase 3 — Isolated Build Workers, Container Deployments & Kubernetes (**Planned**)

- [ ] **GitHub Actions Synchronization:** Ingest and display real CI workflow statuses and commit checks linked to CloudForge projects.
- [ ] **Isolated Container Build Workers:** Execute repository container builds strictly inside ephemeral, rootless workers with enforced CPU, memory, disk, timeout, and network egress limits (never on the control-plane host).
- [ ] **Docker Image Deployment:** Push immutable, content-addressed OCI digests to a configured container registry.
- [ ] **Kubernetes Orchestration (`infrastructure/kubernetes/`):** Deploy projects to controlled local (kind/k3d) and remote Kubernetes namespaces using least-privilege service accounts, readiness/liveness probes, and `NetworkPolicy` isolation.
- [ ] **Deployment History & Rollback:** Persist every release revision (commit SHA, image digest, manifest checksum, actor, outcome) and provide one-click rollback to previously verified healthy releases.

---

## Phase 4 — Observability & DevSecOps Automation (**Planned**)

- [ ] **Prometheus Integration (`infrastructure/monitoring/`):** Expose `/actuator/prometheus` via Micrometer and scrape API, PostgreSQL, build worker, and Kubernetes cluster metrics.
- [ ] **Grafana Dashboards & Alerting:** Ship version-controlled Grafana dashboards and alerting rules for latency, error rates, database pool exhaustion, and failed rollouts.
- [ ] **DevSecOps Policy Gates:** Automate secret scanning, Maven/npm dependency CVE checks, container image scanning (Trivy), SBOM generation (Syft), and image signing (Cosign) prior to Kubernetes promotion.
- [ ] **Production Frontend Container & Ingress:** Add hardened NGINX/static frontend image and TLS ingress templates under `infrastructure/docker/` and `infrastructure/kubernetes/`.
