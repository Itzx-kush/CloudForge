# CloudForge

**Self-Service Cloud Deployment and DevOps Automation Platform**

> **Current Milestone:** Phase 2 — Identity, GitHub Integration & Persistent Workspaces (**Implemented**).
> Container builds, encrypted secret stores, Kubernetes rollouts, rollback, Prometheus/Grafana monitoring, and DevSecOps gates remain **Planned** for Phases 3–4 and are clearly marked throughout the platform and documentation.

---

## 1. Project Overview

CloudForge is a cloud-native self-service deployment and DevOps automation platform giving engineering teams a unified, secure control plane for managing workspaces, connecting GitHub repositories, configuring application environments, and (in Phases 3–4) running isolated builds and Kubernetes deployments.

**Phase 1 (Implemented)** established the full-stack foundation (React 19 + TypeScript + Vite console, Java 17 Spring Boot API, real `GET /api/health` PostgreSQL 17 JDBC probe, Docker Compose, structured logging, CI).

**Phase 2 (Implemented)** transforms CloudForge into a secure, database-backed multi-workspace platform:
- **GitHub OAuth 2.0 Authentication:** Spring Security authorization-code login (`read:user`, `user:email`), HTTP-only server-side sessions (`CLOUDFORGE_SESSION`), SPA CSRF protection (`X-XSRF-TOKEN`), open-redirect protection, `GET /api/auth/me`, and `POST /api/auth/logout`.
- **Flyway-Managed PostgreSQL 17 Schema:** Versioned SQL migrations (`V1`, `V2`) managing `users`, `workspaces`, `workspace_members`, `github_installations`, `linked_repositories`, `projects`, `project_environments`, `audit_events`, and `webhook_deliveries`.
- **Workspace Role-Based Authorization (RBAC):** Backend-enforced `OWNER`, `DEVELOPER`, and `VIEWER` roles with strict cross-workspace isolation and audit trails.
- **Dedicated GitHub App Integration:** RS256 App JWT signing, short-lived in-memory installation tokens (`metadata: read`), state-bound installation callback & ownership validation, repository discovery/sync, and revoked/suspended/rate-limit handling.
- **Verified GitHub Webhooks:** Constant-time HMAC-SHA256 verification (`X-Hub-Signature-256`) over raw request bytes with database-backed delivery deduplication for installation and repository lifecycle events.
- **Projects & Persistent Environments:** Full PostgreSQL CRUD for projects, repository linking, and `Development`/`Staging`/`Production` non-secret configurations (actively rejecting plaintext secrets).
- **Complete Dark-Themed React UI:** Login screen, protected routes, workspace switcher, authenticated user profile & logout, real PostgreSQL dashboard counters, Projects CRUD & Environment editor, Repositories management, and Settings (Members, RBAC, Audit History).

---

## 2. Technology Stack

| Layer | Implemented (Phases 1–2) | Planned (Phases 3–4) |
| --- | --- | --- |
| **Frontend** | React 19, TypeScript, Vite, React Router 7, Lucide Icons, Obsidian Dark Design System | Real-time build log streaming, interactive rollout/rollback controls |
| **Backend** | Java 17, Spring Boot 3.4, Maven 3.9, Spring Security 6, OAuth2 Client, Spring Data JPA, Flyway 10, BouncyCastle, Nimbus JOSE+JWT, Spring Boot Actuator | Kubernetes Java Client, Encrypted Secrets KMS integration |
| **Database** | PostgreSQL 17 (`postgres:17-alpine`) via Docker Compose + Flyway versioned migrations | Deployment revision ledger, build job tables |
| **Containers & Orchestration** | Multi-stage Dockerfiles, Docker Compose (WSL 2 compatible) | Rootless isolated build workers, Kubernetes manifests & Helm charts |
| **CI/CD & DevSecOps** | GitHub Actions CI (`.github/workflows/ci.yml`), HMAC-SHA256 webhooks | Tenant pipeline orchestration, Trivy, Syft SBOM, Cosign signing |
| **Observability** | `GET /api/health`, Actuator (`/actuator/health`, `/actuator/info`), MDC request IDs, Workspace Audit Logs | Prometheus scraping, Grafana dashboards, Alertmanager |
| **Testing** | JUnit 5, Spring Boot Test, MockMvc, Spring Security Test, H2 (PG mode), Vitest, React Testing Library | Live cluster & end-to-end deployment smoke tests |

---

## 3. Repository Structure

```text
CloudForge/
├── frontend/                   # React 19 + TypeScript + Vite developer console
│   ├── src/
│   │   ├── components/         # Layout, Sidebar, Header, ProtectedRoute, Modal, DashboardCard, DataTable, StatusBadge, etc.
│   │   ├── context/            # AuthContext (session, workspaces, role, logout)
│   │   ├── hooks/              # Live backend health probe hook
│   │   ├── pages/              # Login, Dashboard, Projects, ProjectDetail, Repositories, Settings, Deployments, Pipelines, Monitoring
│   │   ├── services/           # CSRF-aware API client (auth, workspaces, projects, repos, audit, health)
│   │   ├── styles/             # Obsidian dark design tokens & global styles
│   │   ├── test/               # Vitest + React Testing Library suite
│   │   └── types/              # Shared TypeScript interfaces
│   └── vite.config.ts          # Dev server & same-origin proxy (/api, /oauth2, /login/oauth2, /actuator)
├── backend/                    # Java 17 Maven Spring Boot API
│   ├── src/main/java/com/cloudforge/backend/
│   │   ├── config/             # AppProperties, WebConfig, RequestCorrelationFilter
│   │   ├── controller/         # Health, Auth, Workspace, Project, GitHubIntegration, GitHubWebhook, AuditEvent
│   │   ├── dto/                # Validated Java 17 request/response records
│   │   ├── entity/             # BaseAuditableEntity + 9 Phase 2 JPA entities & enums
│   │   ├── exception/          # GlobalExceptionHandler & typed API exceptions
│   │   ├── github/             # GitHubAppTokenService, GitHubApiClient, RestClientGitHubApiClient, WebhookSignatureVerifier
│   │   ├── repository/         # DatabaseProbeRepository + 9 Spring Data JPA repositories
│   │   ├── security/           # SecurityConfig, OAuth2 user service & handlers, SPA CSRF, RedirectOriginValidator
│   │   └── service/            # Health, UserAccount, WorkspaceAuthorization, Workspace, Project, Environment, GitHub, Webhook, Audit
│   ├── src/main/resources/
│   │   ├── db/migration/       # Flyway SQL migrations (V1__phase2_core_schema.sql, V2__webhook_deliveries.sql)
│   │   ├── application.yml
│   │   └── logback-spring.xml
│   ├── src/test/               # JUnit 5 unit, Flyway migration, security, RBAC, GitHub App & webhook tests
│   └── Dockerfile              # Multi-stage Maven build + non-root Temurin 17 runtime
├── docs/
│   ├── ARCHITECTURE.md         # Implemented Phases 1–2 & planned Phases 3–4 architecture
│   ├── DEVELOPMENT.md          # Local development, migrations, testing & troubleshooting
│   ├── GITHUB_INTEGRATION.md   # Complete GitHub OAuth App, GitHub App & Webhook setup guide
│   └── ROADMAP.md              # Implemented vs. planned milestone tracker
├── compose.yaml                # PostgreSQL 17 (persistent volume) + Spring Boot backend
└── .env.example                # Local development & GitHub integration variable template
```

---

## 4. Quick Start

1. **Create your local environment file (ignored by Git):**
   ```powershell
   Copy-Item .env.example .env
   ```
2. **Optional — Add GitHub OAuth & GitHub App credentials:**
   See [`docs/GITHUB_INTEGRATION.md`](docs/GITHUB_INTEGRATION.md) for exact callback URLs, permissions, and environment variables.
3. **Start PostgreSQL 17 and the Spring Boot backend (applies Flyway migrations automatically):**
   ```powershell
   docker compose up -d --build
   ```
4. **Start the React frontend:**
   ```powershell
   cd frontend
   npm install
   npm run dev
   ```
5. Open **http://localhost:5173**.

---

## 5. Key Backend APIs (Phase 1 & Phase 2)

| Category | Endpoints | Authorization |
| --- | --- | --- |
| **Health & Ops** | `GET /api/health`, `GET /actuator/health`, `GET /actuator/info` | Public |
| **Authentication** | `GET /api/auth/config`, `GET /api/auth/csrf`, `GET /api/auth/me`, `POST /api/auth/logout`, `/oauth2/authorization/github` | Config/CSRF public; Me/Logout authenticated |
| **Workspaces & RBAC** | `GET/POST /api/workspaces`, `GET/PATCH /api/workspaces/{id}`, `GET/POST /api/workspaces/{id}/members`, `PATCH/DELETE /api/workspaces/{id}/members/{memberId}` | Membership + `OWNER` for mutations |
| **Projects & Environments** | `GET/POST /api/workspaces/{id}/projects`, `GET/PUT/DELETE .../projects/{projectId}`, `GET/POST/PUT/DELETE .../projects/{projectId}/environments[/{envId}]` | `VIEWER` read, `DEVELOPER` create/update + Dev env, `OWNER` full control & delete |
| **GitHub App & Repos** | `GET .../github/installations`, `POST .../github/install-url`, `GET /api/github/app/callback`, `POST .../installations/{instId}/sync`, `DELETE .../installations/{instId}`, `GET .../repositories[/{repoId}]` | `OWNER` for connections/sync; all members read repos |
| **Audit Logs** | `GET /api/workspaces/{id}/audit-events` | `OWNER` only |
| **Environment Secrets (Phase 3.4)** | `GET .../secrets/status`, `GET/POST/DELETE .../environments/{envId}/secrets`, `POST .../rotate`, `POST .../revoke` | `OWNER` only (write-only metadata; fail closed if unconfigured) |
| **Pipeline Orchestration (Phases 3.2–3.4)** | `GET/POST/PUT .../pipelines/definitions`, `GET/POST .../pipelines/runs`, `POST .../cancel`, worker pool/attempt/log APIs | `OWNER`/`DEVELOPER` non-secret runs; `OWNER` secret bindings & secret runs |
| **Internal Worker & Operator APIs** | `/api/internal/workers/**`, `/api/internal/operator/secrets/**` | Dedicated Bearer token filters; disabled by default |
| **GitHub Webhooks** | `POST /api/webhooks/github` | `X-Hub-Signature-256` HMAC-SHA256 verification |

---

The [Phase 3.5 source acquisition draft](docs/PHASE3_5_CONTAINER_BUILDS_OCI_REGISTRY.md) adds an opt-in pipeline stage for pinned GitHub archives on a dedicated worker.

## 6. Testing

```powershell
# Backend (JUnit 5, MockMvc, Spring Security Test, Flyway, H2)
cd backend
mvn -B test

# Frontend (Vitest, React Testing Library, TypeScript, Vite production build)
cd frontend
npm test
npm run typecheck
npm run build
```

---

## 7. Security Considerations & Scope Boundaries

- **Server-Side Sessions & CSRF:** HTTP-only `CLOUDFORGE_SESSION` cookie + `X-XSRF-TOKEN` CSRF enforcement on authenticated state-changing requests. Zero GitHub access tokens are exposed to browser JavaScript or logs.
- **Backend RBAC & Workspace Isolation:** Every workspace resource enforces role checks and workspace ownership on the backend; cross-workspace reads, mutations, and repository bindings are blocked.
- **Plaintext Config Variables Rejected When Secret-Like:** Project environment configurations support non-secret values only; sensitive credentials belong in the **Phase 3.4 AES-256-GCM Encrypted Environment Secrets** vault (`OWNER`-only write-only metadata APIs, queue-time pinned references, lease-bound worker delivery, disabled until configured).
- **No Untrusted Arbitrary Builds or Kubernetes Deployments:** Worker execution remains scoped strictly to allowlisted operations (`ENV_VALIDATION`, `CONTROLLED_SMOKE_TEST`, `WORKER_DIAGNOSTICS`); OCI registry pushes and Kubernetes rollouts remain planned for Phases 3.5–3.7.
- **External Credentials:** Live GitHub OAuth sign-in, GitHub App installations, public webhook deliveries, and Phase 3.4 master keyrings require configuring your own credentials in `.env` (see [`docs/GITHUB_INTEGRATION.md`](docs/GITHUB_INTEGRATION.md) and [`docs/PHASE3_4_SECRET_MANAGEMENT.md`](docs/PHASE3_4_SECRET_MANAGEMENT.md)).

---

## License

This project is licensed under the MIT License — see [`LICENSE`](LICENSE) for details.
