# CloudForge Local Development Guide

This guide covers setting up, running, testing, migrating, and troubleshooting CloudForge (Phases 1 & 2) on Windows 11 (PowerShell + WSL 2 Docker Desktop), macOS, and Linux.

---

## 1. Environment Setup

1. Verify installed tool versions:
   ```powershell
   java -version        # Java 17
   mvn -version         # Apache Maven 3.9+
   node -v              # Node.js 24+ (20+ compatible)
   npm -v               # npm 11+
   docker --version
   docker compose version
   ```

2. Create a local `.env` file from `.env.example` (never committed to Git):
   ```powershell
   Copy-Item .env.example .env
   git check-ignore -v .env
   ```

3. *(Optional for live GitHub sign-in and repository sync)* Configure your GitHub OAuth App and GitHub App environment variables in `.env` following [`docs/GITHUB_INTEGRATION.md`](GITHUB_INTEGRATION.md). Without GitHub credentials, the backend and database still start cleanly, `/api/health` remains operational, and the UI displays explicit **Setup Required** states.

---

## 2. Running the Stack Locally

### Recommended Workflow (Docker Compose Backend + PostgreSQL, Local Vite Frontend)

1. Build and start PostgreSQL 17 and the Spring Boot backend (preserves existing `cloudforge_postgres_data` volume and applies Flyway migrations `V1` and `V2` automatically):
   ```powershell
   docker compose up -d --build
   ```

2. Verify container health and Flyway migrations:
   ```powershell
   docker compose ps
   docker compose logs --tail=50 backend
   docker exec cloudforge-postgres psql -U cloudforge -d cloudforge -c "\dt"
   ```
   Wait until both `cloudforge-postgres` and `cloudforge-backend` report `(healthy)`.

3. Test the public backend health and auth config APIs:
   ```powershell
   Invoke-RestMethod http://localhost:8080/api/health
   Invoke-RestMethod http://localhost:8080/api/auth/config
   ```

4. Install and start the React frontend:
   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

5. Open **http://localhost:5173**. Vite (`frontend/vite.config.ts`) proxies `/api/*`, `/actuator/*`, `/oauth2/*`, and `/login/oauth2/*` to `http://localhost:8080` with `X-Forwarded-*` headers enabled so HTTP-only session cookies and CSRF cookies stay same-origin.

### Host-Native Backend Workflow (Rapid Java Iteration)

1. Start only PostgreSQL 17 in Docker:
   ```powershell
   docker compose up -d postgres
   ```
2. Run Spring Boot with Maven on the host (connects to `localhost:5432` and runs Flyway migrations):
   ```powershell
   cd backend
   mvn spring-boot:run
   ```
3. Run the Vite frontend in another terminal:
   ```powershell
   cd frontend
   npm run dev
   ```

---

## 3. Database Migrations (Flyway)

- Migrations live in `backend/src/main/resources/db/migration/` (`V1__phase2_core_schema.sql`, `V2__webhook_deliveries.sql`).
- Hibernate DDL generation is disabled (`spring.jpa.hibernate.ddl-auto: none` in runtime; `validate` in test profile).
- **Do not run `docker compose down -v`** unless you explicitly intend to wipe your local PostgreSQL volume. Use `docker compose down` or `docker compose up -d --build` to preserve existing data across restarts.
- Inspect applied migrations in PostgreSQL:
  ```powershell
  docker exec cloudforge-postgres psql -U cloudforge -d cloudforge -c "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;"
  ```

---

## 4. Running Automated Tests & Builds

### Backend (JUnit 5, MockMvc, Spring Security Test, Flyway, H2)

```powershell
cd backend
mvn -B test
mvn -B clean package
```

Notes:
- Backend tests run under the `test` Spring profile (`application-test.yml`) with Flyway migrations executed against H2 in PostgreSQL compatibility mode and Hibernate schema validation (`ddl-auto: validate`).
- External GitHub API calls in `GitHubAppAndRepositoriesIntegrationTest` are mocked via `GitHubApiClient`; cryptographic JWT signing (`GitHubAppTokenServiceTest`) and webhook HMAC-SHA256 signature verification (`GitHubWebhookIntegrationTest`) execute real JCA/Nimbus/BouncyCastle crypto over ephemeral test keys and secrets.
- Live PostgreSQL 17 Flyway migration execution is verified via Docker Compose (`docker compose up -d --build`).

### Frontend (Vitest, React Testing Library, TypeScript, Vite)

```powershell
cd frontend
npm test
npm run typecheck
npm run build
```

---

## 5. Verifying Degraded Database Handling

To confirm `GET /api/health` still performs a real database check in Phase 2:

```powershell
docker compose stop postgres
curl -i http://localhost:8080/api/health   # Expect HTTP 503 DEGRADED
docker compose start postgres
curl -i http://localhost:8080/api/health   # Expect HTTP 200 UP
```

---

## 6. Logging & Security Debugging

- Every HTTP response includes `X-Request-Id` propagated to SLF4J MDC (`req=<id>`).
- Authentication tokens, OAuth client secrets, GitHub App private keys, and webhook signatures are never logged.
- Increase application log verbosity locally via `LOG_LEVEL_APP=DEBUG` in `.env`.

---

## 7. Common Troubleshooting

| Issue | Likely Cause | Fix |
| --- | --- | --- |
| Login screen says **GitHub OAuth Setup Required** | `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET` are unset in `.env` | Follow [`docs/GITHUB_INTEGRATION.md`](GITHUB_INTEGRATION.md) to configure your OAuth App and restart the backend |
| Repositories screen says **GitHub App Configuration Required** | `GITHUB_APP_ID`, `GITHUB_APP_SLUG`, or private key unset | Configure GitHub App credentials in `.env` and restart the backend |
| Mutating API call returns `403 Forbidden` | Missing/stale `X-XSRF-TOKEN` header or insufficient workspace role (`VIEWER`/`DEVELOPER`) | Ensure browser calls use `credentials: 'include'` with `X-XSRF-TOKEN`, or verify your workspace role in **Settings** |
| Environment save returns `400 Bad Request` | Variable key or value matches secret pattern (`*SECRET*`, `*PASSWORD*`, `*TOKEN*`, etc.) | Remove secret values; Phase 2 supports non-secret configuration only |
| `compose.yaml` port bind failure on `5432` or `8080` | Another local PostgreSQL or Java service uses that port | Stop the conflicting service or override `POSTGRES_PORT` / `BACKEND_PORT` in `.env` |
