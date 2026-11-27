# CloudForge Local Development Guide

This guide covers setting up, running, testing, migrating, and troubleshooting **CloudForge v1.0.0 (Phases 1–4)** on Windows 11 (PowerShell + WSL 2 Docker Desktop), macOS, and Linux.

---

## 1. Prerequisites & Environment Setup

1. Verify installed tool versions:
   ```powershell
   java -version        # Java 17 (Temurin)
   mvn -version         # Apache Maven 3.9+
   node -v              # Node.js 24+ (20+ compatible)
   npm -v               # npm 11+
   docker --version
   docker compose version
   kubectl version --client   # Optional: local Kubernetes testing
   ```

2. Create a local `.env` file from `.env.example` (ignored by Git):
   ```powershell
   Copy-Item .env.example .env
   git check-ignore -v .env
   ```

3. *(Optional for live GitHub sign-in and repository sync)* Configure your GitHub OAuth App and GitHub App environment variables in `.env` following [`docs/GITHUB_INTEGRATION.md`](GITHUB_INTEGRATION.md). Without GitHub credentials, the backend and database still start cleanly, `/api/health` remains operational, and the UI displays explicit **Setup Required** states.

---

## 2. Running the Stack Locally

### Recommended Workflow (Docker Compose Backend + PostgreSQL, Local Vite Frontend)

1. Build and start PostgreSQL 17 and the Spring Boot backend (preserves existing `cloudforge_postgres_data` volume and applies Flyway migrations `V1`–`V11` automatically):
   ```powershell
   docker compose up -d --build
   ```

2. Verify container health and Flyway migrations:
   ```powershell
   docker compose ps
   docker compose logs --tail=50 backend
   docker exec cloudforge-postgres psql -U cloudforge -d cloudforge -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
   ```

3. Test the public backend health and auth config APIs:
   ```powershell
   Invoke-RestMethod http://localhost:8080/api/health
   Invoke-RestMethod http://localhost:8080/api/auth/config
   ```

4. Start the React frontend:
   ```powershell
   cd frontend
   npm ci
   npm run dev
   ```

5. Open **http://localhost:5173**. Vite proxies `/api/*`, `/actuator/*`, `/oauth2/*`, and `/login/oauth2/*` to `http://localhost:8080` with `X-Forwarded-*` headers so HTTP-only session cookies and CSRF cookies stay same-origin.

### Host-Native Backend Workflow (Rapid Java Iteration)

```powershell
# Terminal 1: Start only PostgreSQL 17 in Docker
docker compose up -d postgres

# Terminal 2: Run Spring Boot on the host
cd backend
mvn spring-boot:run

# Terminal 3: Run the Vite frontend
cd frontend
npm run dev
```

---

## 3. Database Migrations (Flyway `V1`–`V11`)

- Migrations live in `backend/src/main/resources/db/migration/` (`V1__phase2_core_schema.sql` through `V11__security_evidence.sql`, creating 37 application tables).
- Hibernate DDL generation is disabled (`spring.jpa.hibernate.ddl-auto: none` in runtime; `validate` in test profile).
- **Never run `docker compose down -v`** unless you explicitly intend to wipe your local PostgreSQL volume. Use `docker compose down` or `docker compose up -d --build` to preserve data.
- Never modify applied migrations or run `flyway clean` against shared/staging databases.

---

## 4. Running Optional Execution & Observability Components

Every component below is modular and can be enabled independently without running the entire stack:

### A. Host-Side Isolated Worker (`worker/`)
By default, worker execution is **disabled** (`CLOUDFORGE_WORKERS_ENABLED=false`, locked `false` in `application-render.yml`). Never mount `/var/run/docker.sock` into the Spring Boot backend container.

1. Generate a per-worker secret (`>= 16` chars) in `.env`:
   ```text
   CLOUDFORGE_WORKERS_ENABLED=true
   CLOUDFORGE_WORKERS_SCHEDULER_ENABLED=true
   CLOUDFORGE_WORKER_CREDENTIALS=local-docker-worker-1:key-local-1:<32-char-secret>
   ```
2. Package and run the standalone worker JAR:
   ```powershell
   cd worker
   mvn -B package -DskipTests
   docker pull alpine:3.20
   $env:CLOUDFORGE_CONTROL_PLANE_URL = "http://127.0.0.1:8080"
   $env:CLOUDFORGE_WORKER_ID = "local-docker-worker-1"
   $env:CLOUDFORGE_WORKER_TOKEN = "<32-char-secret>"
   $env:CLOUDFORGE_EXECUTOR_IMAGE = "alpine:3.20"
   # Optional: publish Prometheus worker textfile metrics (works on Windows and Linux)
   $env:CLOUDFORGE_WORKER_METRICS_DIR = "$env:TEMP\cloudforge-worker-metrics"
   java -jar target/cloudforge-worker.jar
   ```
3. See [`docs/PHASE3_4_SECRET_MANAGEMENT.md`](PHASE3_4_SECRET_MANAGEMENT.md), [`docs/PHASE3_5_CONTAINER_BUILDS_OCI_REGISTRY.md`](PHASE3_5_CONTAINER_BUILDS_OCI_REGISTRY.md), [`docs/PHASE3_6_KUBERNETES_DEPLOYMENT.md`](PHASE3_6_KUBERNETES_DEPLOYMENT.md), and [`docs/PHASE3_7_RELEASE_HISTORY_ROLLBACK.md`](PHASE3_7_RELEASE_HISTORY_ROLLBACK.md) for dedicated `GITHUB_SOURCE`, `OCI_BUILDKIT`, and `KUBERNETES_DEPLOY` worker flags.

### B. Local Disposable OCI Registry
```powershell
docker run -d --rm --name cloudforge-local-registry -p 127.0.0.1:5000:5000 registry:2
```

### C. Disposable Local Kubernetes Cluster (K3s or kind)
Follow [`infrastructure/kubernetes/README.md`](../infrastructure/kubernetes/README.md) to run a disposable `rancher/k3s` or `kind` cluster with an isolated temporary `KUBECONFIG`, provision a hardened namespace with `provision-local-namespace.ps1`/`.sh`, and verify least-privilege RBAC with `rbac-verification.ps1`/`.sh`.

### D. Optional Phase 4 Monitoring Stack (Prometheus, Grafana, Alertmanager, OTel)
Follow [`infrastructure/monitoring/README.md`](../infrastructure/monitoring/README.md) to configure external secret files and launch:
```powershell
docker compose -f compose.yaml -f compose.monitoring.yaml up -d --build
```

---

## 5. Running Automated Verification Suites

### Backend (JUnit 5, MockMvc, Spring Security Test, Flyway, H2 + Optional Live PostgreSQL 17)
```powershell
cd backend
mvn -B test
mvn -B package -DskipTests
```
To execute `PostgresSearchQueryRegressionTest` against a disposable PostgreSQL 17 container (never mutating your existing local database):
```powershell
docker run -d --rm --name cf-pg-test -e POSTGRES_DB=cloudforge -e POSTGRES_USER=cloudforge -e POSTGRES_PASSWORD=testpw -p 127.0.0.1:15432:5432 postgres:17-alpine
$env:TEST_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:15432/cloudforge"
$env:TEST_POSTGRES_USER = "cloudforge"
$env:TEST_POSTGRES_PASSWORD = "testpw"
$env:TEST_POSTGRES_RUN_FLYWAY = "true"
cd backend
mvn -B test
docker rm -f cf-pg-test
```

### Worker (Unit + Real Docker Isolation + Real BuildKit/Registry:2 + Optional K8s E2E)
```powershell
cd worker
mvn -B test
mvn -B package -DskipTests
```

### Frontend (TypeScript, Vitest + React Testing Library, Vite Production Build)
```powershell
cd frontend
npm ci
npm run typecheck
npm test
npm run build
```

### Docker Compose Config Validation
```powershell
docker compose config --quiet
```

---

## 6. Common Troubleshooting

| Issue | Likely Cause | Fix |
| --- | --- | --- |
| Login screen says **GitHub OAuth Setup Required** | `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET` unset | Configure your OAuth App in `.env` (`docs/GITHUB_INTEGRATION.md`) and restart backend |
| Mutating API returns `403 Forbidden` | Missing/stale `X-XSRF-TOKEN` or insufficient RBAC role | Ensure `credentials: 'include'` + `X-XSRF-TOKEN` header and check role in **Settings** |
| Environment save returns `400 Bad Request` | Key or value matches plaintext secret pattern | Store sensitive values in **Encrypted Environment Secrets** instead |
| `PostgresSearchQueryRegressionTest` skipped locally | Local DB at `localhost:5432` is older than V11 and `TEST_POSTGRES_RUN_FLYWAY` is not `true` | Run against a disposable PostgreSQL 17 container with `TEST_POSTGRES_RUN_FLYWAY=true` |
| `/actuator/prometheus` returns `403` or `404` | `monitoring` profile inactive or missing `>= 32`-char Bearer token | Activate `monitoring` profile and provide `cloudforge.monitoring.token` |
| Production Kubernetes execution rejected (`409 Conflict`) | Missing, expired (>24h), or adverse signed Trivy/Cosign evidence | Run `infrastructure/supplychain/assess-image.sh` on a clean signed image and submit evidence as workspace `OWNER` |
