# CloudForge Local Development Guide

This guide covers setting up, running, testing, and troubleshooting CloudForge Phase 1 on Windows 11 (PowerShell + WSL 2 Docker Desktop), macOS, and Linux.

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

2. Create a local `.env` file from `.env.example`:
   ```powershell
   Copy-Item .env.example .env
   ```
   Confirm `.env` is ignored by Git:
   ```powershell
   git check-ignore -v .env
   ```

---

## 2. Running the Stack Locally

### Recommended Workflow (Docker Compose Backend + PostgreSQL, Local Vite Frontend)

1. Build and start PostgreSQL 17 and the Spring Boot backend:
   ```powershell
   docker compose up -d --build
   ```

2. Verify container health:
   ```powershell
   docker compose ps
   ```
   Wait until both `cloudforge-postgres` and `cloudforge-backend` report `(healthy)`.

3. Test the backend health API directly:
   ```powershell
   Invoke-RestMethod http://localhost:8080/api/health
   ```

4. Install and start the React frontend:
   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

5. Open **http://localhost:5173**. Requests to `/api/*` are proxied automatically by Vite (`frontend/vite.config.ts`) to `http://localhost:8080`.

### Host-Native Backend Workflow (Rapid Java Iteration)

If you are actively editing Java code and want fast restarts without rebuilding the Docker image on every change:

1. Start only PostgreSQL 17 in Docker:
   ```powershell
   docker compose up -d postgres
   ```
2. Run Spring Boot with Maven on the host (connects to `localhost:5432`):
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

## 3. Verifying Degraded Database Handling

To confirm `GET /api/health` performs a real database check rather than returning a hardcoded value:

1. Stop PostgreSQL while the backend remains running:
   ```powershell
   docker compose stop postgres
   ```
2. Call `GET http://localhost:8080/api/health` (or click **Probe Backend Health** in the UI).
   - Expected HTTP status: `503 Service Unavailable`
   - Expected JSON: `"status": "DEGRADED"`, `"database": { "status": "DOWN", ... }`
3. Restart PostgreSQL:
   ```powershell
   docker compose start postgres
   ```
4. Re-check `GET http://localhost:8080/api/health`.
   - Expected HTTP status: `200 OK`
   - Expected JSON: `"status": "UP"`, `"database": { "status": "UP", ... }`

---

## 4. Running Automated Tests & Builds

### Backend

```powershell
cd backend
mvn test
mvn clean package
```

Notes:
- Backend tests run under the `test` Spring profile (`application-test.yml`) using an in-memory H2 database in PostgreSQL compatibility mode so unit and integration tests execute deterministically with zero external dependencies.
- Live PostgreSQL 17 integration is verified via Docker Compose (`docker compose up -d --build`).

### Frontend

```powershell
cd frontend
npm test
npm run typecheck
npm run build
```

---

## 5. Logging & Debugging

- Every HTTP response includes an `X-Request-Id` header generated (or propagated) by `RequestCorrelationFilter`.
- Backend logs include UTC ISO-8601 timestamps, severity level, application name, `req=<requestId>`, thread name, and logger:
  ```text
  2026-11-23T06:30:00.123Z INFO  [cloudforge-backend] [req=a1b2c3d4] [http-nio-8080-exec-1] c.c.backend.service.HealthService - Health check status=UP database=UP engine="PostgreSQL 17.4" responseTimeMs=3
  ```
- Increase backend verbosity locally by setting `LOG_LEVEL_APP=DEBUG` in your local `.env`.

---

## 6. Common Troubleshooting

| Issue | Likely Cause | Fix |
| --- | --- | --- |
| Frontend displays **Spring Boot backend is unreachable** | Backend container is stopped or still booting | Run `docker compose ps` and `docker compose logs backend`; confirm port `8080` is listening |
| `compose.yaml` port bind failure on `5432` or `8080` | Another local PostgreSQL or Java service uses that port | Stop the conflicting service or override `POSTGRES_PORT` / `BACKEND_PORT` in `.env` (and update `VITE_API_PROXY_TARGET` if `BACKEND_PORT` changes) |
| Docker Desktop build slow on Windows | Repository checked out on slow drive or large context | Ensure WSL 2 engine is active in Docker Desktop; `backend/.dockerignore` keeps the build context minimal |
| `git status` shows `.env` | `.env` was force-added previously | Run `git rm --cached .env` and verify `.gitignore` contains `.env` |
