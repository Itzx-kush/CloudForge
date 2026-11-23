# CloudForge

**Self-Service Cloud Deployment and DevOps Automation Platform**

> **Current Milestone:** Phase 1 — Platform Foundation (**Implemented**).
> Advanced workflows (GitHub OAuth/Apps, container builds, Kubernetes rollouts, rollback, Prometheus/Grafana monitoring, and DevSecOps gates) are **Planned** for Phases 2–4 and clearly marked throughout the platform and documentation.

---

## 1. Project Overview

CloudForge is a cloud-native self-service deployment and DevOps automation platform designed to give engineering teams a unified, secure control plane for managing projects, connecting repositories, automating builds, deploying containerized workloads to Kubernetes, and observing system health.

**Phase 1** establishes the verified full-stack foundation:
- A dark-themed **React + TypeScript + Vite** developer console with seven core navigation views and live backend health telemetry.
- A **Java 17 Spring Boot** REST API backed by a real JDBC connectivity probe (`GET /api/health`) and Spring Boot Actuator.
- A **PostgreSQL 17** database running in **Docker Compose** with persistent named storage, health checks, and localhost-only exposure.
- Multi-stage **Docker** containerization, structured request-correlated logging, automated frontend and backend tests, and comprehensive architecture documentation.

---

## 2. Problem Statement

Modern software teams frequently piece together fragmented scripts, ad-hoc CI/CD jobs, manual Kubernetes manifests, and scattered dashboards just to deploy routine services. Without a standardized self-service control plane:
- Onboarding a new service requires repeating brittle infrastructure boilerplate.
- Security practices (non-root containers, least-privilege credentials, secret hygiene, vulnerability scanning) are inconsistently enforced.
- Visibility across repository commits, pipeline runs, release history, rollbacks, and runtime health is fragmented across disconnected tools.

---

## 3. Project Objectives

- **Implemented (Phase 1):**
  - Deliver a clean, modular monorepo architecture (`frontend/`, `backend/`, `infrastructure/`, `docs/`, `.github/workflows/`).
  - Provide a responsive dark-themed developer dashboard displaying real-time Spring Boot and PostgreSQL connectivity.
  - Implement a Java 17 Spring Boot backend exposing `GET /api/health` backed by a real JDBC `SELECT 1` probe with proper `200 OK` / `503 Service Unavailable` semantics.
  - Run PostgreSQL 17 and the Spring Boot backend locally through Docker Compose with named volumes, health checks, and environment-variable configuration.
  - Establish automated frontend and backend test suites and strict secret-hygiene defaults.
- **Planned (Phases 2–4):**
  - Integrate GitHub OAuth and GitHub Apps for least-privilege repository linking and workspace RBAC.
  - Persist projects, environments, and release metadata in PostgreSQL with versioned database migrations.
  - Execute builds inside isolated, resource-limited workers and deploy container images to controlled Kubernetes clusters with full deployment history and instant rollback.
  - Integrate Prometheus, Grafana, and automated DevSecOps gates (secret scanning, dependency audits, container CVE scanning, SBOMs).

---

## 4. Technology Stack

| Layer | Technology (Phase 1 — Implemented) | Planned Extensions (Phases 2–4) |
| --- | --- | --- |
| **Frontend** | React 19, TypeScript, Vite, React Router, Lucide Icons, Modern CSS | Real-time build log streaming, interactive rollout/rollback controls |
| **Backend** | Java 17, Spring Boot 3.4, Maven 3.9, Spring Web, Spring Data JPA, Spring Validation, Spring Boot Actuator | Flyway migrations, Spring Security (GitHub OAuth), Kubernetes Java Client |
| **Database** | PostgreSQL 17 (`postgres:17-alpine`) via Docker Compose | Versioned domain schemas (`projects`, `repositories`, `deployments`, `audit_logs`) |
| **Containers & Orchestration** | Multi-stage Dockerfiles, Docker Compose (WSL 2 compatible) | Rootless isolated build workers, Kubernetes manifests & Helm charts |
| **CI/CD & DevSecOps** | GitHub Actions Phase 1 CI (`.github/workflows/ci.yml`) | Tenant pipeline orchestration, Trivy, Syft SBOM, Cosign signing |
| **Observability** | `GET /api/health`, Actuator (`/actuator/health`, `/actuator/info`), MDC structured logs | Prometheus metrics scraping, Grafana dashboards, Alertmanager |
| **Testing** | JUnit 5, Spring Boot Test, MockMvc, H2 (test scope), Vitest, React Testing Library | Testcontainers (PostgreSQL), end-to-end smoke tests |

---

## 5. Architecture Overview

```text
+-------------------------------------------------------------------+
|  Browser (Developer Console — React + TypeScript + Vite :5173)    |
|  - Dashboard, Projects, Repositories, Deployments, Pipelines,     |
|    Monitoring, Settings                                           |
|  - Vite dev proxy forwards /api/* -> http://localhost:8080        |
+---------------------------------+---------------------------------+
                                  | HTTP (GET /api/health)
                                  v
+-------------------------------------------------------------------+
|  Docker Compose Network (cloudforge-net)                          |
|                                                                   |
|  +-------------------------------------------------------------+  |
|  |  Spring Boot Backend (Java 17, port 127.0.0.1:8080 -> 8080) |  |
|  |  - RequestCorrelationFilter (X-Request-Id / SLF4J MDC)      |  |
|  |  - HealthController -> HealthService -> DatabaseProbeRepo   |  |
|  |  - Spring Boot Actuator (/actuator/health, /actuator/info)  |  |
|  +------------------------------+------------------------------+  |
|                                 | JDBC (postgres:5432, SELECT 1)  |
|                                 v                                 |
|  +-------------------------------------------------------------+  |
|  |  PostgreSQL 17 (postgres:17-alpine, 127.0.0.1:5432 -> 5432) |  |
|  |  - Persistent volume: cloudforge_postgres_data              |  |
|  |  - Healthcheck: pg_isready                                  |  |
|  +-------------------------------------------------------------+  |
+-------------------------------------------------------------------+
```

For deeper architectural details, package boundaries, and future-phase blueprints, see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## 6. Repository Structure

```text
CloudForge/
├── frontend/                   # React + TypeScript + Vite developer dashboard
│   ├── public/                 # Static assets (favicon)
│   ├── src/
│   │   ├── components/         # Sidebar, Header, StatusBadge, DashboardCard, PageContainer, LoadingState, ErrorState, DataTable, Layout
│   │   ├── hooks/              # Live health polling/probe hook
│   │   ├── pages/              # Dashboard, Projects, Repositories, Deployments, Pipelines, Monitoring, Settings
│   │   ├── services/           # API client calling /api/health
│   │   ├── styles/             # Design tokens & global dark theme styles
│   │   ├── test/               # Vitest + React Testing Library suite
│   │   └── types/              # Shared TypeScript interfaces
│   ├── package.json
│   └── vite.config.ts          # Dev server & /api proxy to Spring Boot
├── backend/                    # Java 17 Maven Spring Boot API
│   ├── src/main/java/com/cloudforge/backend/
│   │   ├── config/             # AppProperties, WebConfig (CORS), RequestCorrelationFilter
│   │   ├── controller/         # HealthController (GET /api/health)
│   │   ├── dto/                # HealthResponse, DatabaseHealthDto, ApiErrorResponse
│   │   ├── entity/             # BaseAuditableEntity (Phase 2+ JPA foundation)
│   │   ├── exception/          # GlobalExceptionHandler
│   │   ├── repository/         # DatabaseProbeRepository (real JDBC SELECT 1 check)
│   │   └── service/            # HealthService
│   ├── src/main/resources/     # application.yml, logback-spring.xml
│   ├── src/test/               # JUnit 5, MockMvc, and SpringBootTest suite
│   ├── Dockerfile              # Multi-stage Maven build + non-root Temurin 17 runtime
│   └── pom.xml
├── infrastructure/
│   ├── docker/                 # Future shared container templates (Phase 1 README)
│   ├── kubernetes/             # Planned Kubernetes manifests & RBAC (Phase 3+)
│   └── monitoring/             # Planned Prometheus & Grafana configs (Phase 4)
├── docs/
│   ├── ARCHITECTURE.md         # System design, data flows, and planned component architecture
│   ├── DEVELOPMENT.md          # Local development, debugging, and troubleshooting guide
│   └── ROADMAP.md              # Phased roadmap distinguishing implemented vs. planned work
├── .github/
│   └── workflows/
│       └── ci.yml              # Automated Phase 1 CI workflow (backend tests + frontend build/tests)
├── compose.yaml                # PostgreSQL 17 + Spring Boot backend orchestration
├── .env.example                # Safe local development environment variable template
├── .gitignore                  # Comprehensive exclusions (Java, Maven, Node, IDEs, .env, logs)
├── LICENSE                     # MIT License
└── README.md
```

---

## 7. Prerequisites

- **Java JDK 17** (e.g., Eclipse Temurin 17)
- **Apache Maven 3.9+**
- **Node.js 24+** and **npm 11+** (Node 20+ compatible)
- **Docker Desktop** with **Docker Compose v2+** (WSL 2 backend on Windows 11)
- **Git**

---

## 8. Installation Instructions

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Itzx-kush/CloudForge.git
   cd CloudForge
   ```

2. **Create your local environment file (never committed to Git):**
   ```bash
   # PowerShell (Windows)
   Copy-Item .env.example .env

   # Bash / WSL / macOS / Linux
   cp .env.example .env
   ```

3. **Install frontend dependencies:**
   ```bash
   cd frontend
   npm install
   cd ..
   ```

---

## 9. Local Development Commands

### Option A — Recommended: Backend + PostgreSQL in Docker Compose, Frontend via Vite

1. **Start PostgreSQL 17 and the Spring Boot backend:**
   ```bash
   docker compose up -d --build
   ```

2. **Start the React development server (with `/api` proxy to `http://localhost:8080`):**
   ```bash
   cd frontend
   npm run dev
   ```

3. Open **http://localhost:5173** in your browser. The Dashboard and Monitoring views immediately display live connectivity to the Spring Boot API and PostgreSQL 17.

### Option B — Run Spring Boot directly on the host with Maven (PostgreSQL in Docker)

1. **Start only PostgreSQL 17:**
   ```bash
   docker compose up -d postgres
   ```

2. **Run Spring Boot locally with Maven:**
   ```bash
   cd backend
   mvn spring-boot:run
   ```

3. **Start the React frontend:**
   ```bash
   cd frontend
   npm run dev
   ```

---

## 10. Docker Compose Commands

```bash
# Build backend image and start PostgreSQL 17 + Spring Boot in the background
docker compose up -d --build

# Check container status and health checks
docker compose ps

# Tail backend and database logs
docker compose logs -f backend postgres

# Stop containers while preserving the PostgreSQL named volume
docker compose down

# Stop containers AND delete the persistent PostgreSQL volume (fresh reset)
docker compose down -v
```

---

## 11. Backend API Documentation

### `GET /api/health`

Performs a live JDBC connection check (`connection.isValid` + `SELECT 1`) against the configured PostgreSQL database and reports application and dependency health.

- **Healthy Response — `200 OK`**
  ```json
  {
    "application": "CloudForge Backend",
    "version": "0.1.0-phase1",
    "phase": "Phase 1 — Platform Foundation",
    "status": "UP",
    "database": {
      "status": "UP",
      "engine": "PostgreSQL 17.4",
      "responseTimeMs": 4,
      "message": "Database connection verified"
    },
    "timestamp": "2026-11-23T06:30:00.123456Z"
  }
  ```

- **Degraded Response (Database Unavailable) — `503 Service Unavailable`**
  ```json
  {
    "application": "CloudForge Backend",
    "version": "0.1.0-phase1",
    "phase": "Phase 1 — Platform Foundation",
    "status": "DEGRADED",
    "database": {
      "status": "DOWN",
      "engine": "PostgreSQL",
      "responseTimeMs": 3001,
      "message": "Database connectivity check failed"
    },
    "timestamp": "2026-11-23T06:35:00.123456Z"
  }
  ```

- **Operational Actuator Endpoints (Phase 1):**
  - `GET /actuator/health` — Spring Boot Actuator health check (including liveness/readiness probes).
  - `GET /actuator/info` — Application metadata.

- **Error Responses:**
  All error responses (`400`, `404`, `405`, `500`) return a consistent JSON `ApiErrorResponse` containing `timestamp`, `status`, `error`, `message`, `path`, `requestId`, and `fieldErrors` without exposing stack traces or credentials.

---

## 12. Testing Instructions

### Backend Tests (JUnit 5, MockMvc, Spring Boot Test)

```bash
cd backend
mvn test
```

### Frontend Tests, Typecheck & Production Build

```bash
cd frontend
npm test
npm run typecheck
npm run build
```

### Live Health Verification (with Docker Compose running)

```bash
# Direct backend check
curl -i http://localhost:8080/api/health

# Through Vite development proxy (while npm run dev is running)
curl -i http://localhost:5173/api/health
```

---

## 13. Development Roadmap

| Phase | Milestone | Status | Key Deliverables |
| --- | --- | --- | --- |
| **Phase 1** | Platform Foundation | **Implemented** | Monorepo layout, React/TS dark console (7 views), Java 17 Spring Boot API, real PostgreSQL 17 health check, multi-stage Dockerfile, Docker Compose, tests, docs |
| **Phase 2** | Identity, GitHub & Projects | **Planned** | GitHub OAuth & GitHub App integration, Flyway migrations, Projects & Repositories CRUD APIs, workspace RBAC |
| **Phase 3** | Isolated Builds & Kubernetes Deployments | **Planned** | Sandboxed rootless build workers, OCI image deployments, GitHub Actions sync, Kubernetes rollout controller, deployment history & rollback |
| **Phase 4** | Observability & DevSecOps | **Planned** | Prometheus scraping, provisioned Grafana dashboards, alerting, Trivy/SBOM/secret scanning security gates |

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for detailed phase deliverables and dependencies.

---

## 14. Security Considerations

- **No Arbitrary Repository Execution in Phase 1:** CloudForge does not clone, build, or execute external GitHub repository code in Phase 1. Future build automation will run strictly inside isolated, ephemeral, non-root workers with hard CPU, memory, disk, time, and network egress limits.
- **Least-Privilege & Controlled Targets:** Future GitHub integrations will use short-lived GitHub App installation tokens and signed webhooks rather than broad personal access tokens, deploying only to explicitly registered, namespace-scoped Kubernetes service accounts.
- **Localhost-Only Database Exposure:** `compose.yaml` binds PostgreSQL strictly to `127.0.0.1:5432` and the backend to `127.0.0.1:8080` for local development, preventing unintended LAN/public exposure.
- **Non-Root Container Execution:** The Spring Boot runtime image runs as an unprivileged `cloudforge` user on Alpine Temurin 17 JRE.
- **Secret Hygiene:** `.env` files and private key materials are ignored by `.gitignore`. `.env.example` contains only clearly labeled local-development placeholder values. Error responses and warning logs avoid leaking connection strings or credentials.
- **Authentication Scope:** Authentication and GitHub OAuth are intentionally excluded from Phase 1 local foundation and will be introduced in Phase 2 prior to any multi-user or networked deployment.

---

## License

This project is licensed under the MIT License — see [`LICENSE`](LICENSE) for details.
