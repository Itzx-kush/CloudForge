# CloudForge Final Render Deployment Procedure

This document defines the exact, non-destructive release procedure for updating the existing Render staging deployment of CloudForge to the finalized `v1.0.0` (`Phases 1–4`) release on `main`.

> **NON-NEGOTIABLE GUARDRAIL:** Do **not** trigger live deployment automatically. Stop at **Stage B (Approval Checkpoint)** until the project owner explicitly authorizes deployment. Do **not** delete or reset `cloudforge-staging-db`, modify applied migration checksums, run `flyway clean`, or create a replacement service or paid database.

---

## Target Infrastructure (Existing — Preserve Unchanged)

| Parameter | Value |
| --- | --- |
| **Web Service** | `cloudforge-staging` (`srv-dc24cj6q1p3s73bo4ekg`) |
| **Dashboard** | https://dashboard.render.com/web/srv-dc24cj6q1p3s73bo4ekg |
| **Public URL** | https://cloudforge-staging.onrender.com |
| **GitHub Repo / Branch** | `https://github.com/Itzx-kush/CloudForge` / `main` |
| **Runtime / Dockerfile** | Docker / `./Dockerfile.render` |
| **Region / Plan** | Singapore / Free (`512 MiB` RAM) |
| **Auto-Deploy** | Disabled (manual release control) |
| **PostgreSQL Database** | `cloudforge-staging-db` (`dpg-dc24bu56e07s73etkms0-a`, DB `cloudforge_staging_db`, PostgreSQL 17, Singapore, Free) |
| **DB Free-Plan Expiration** | **December 23, 2026** |
| **Last Audited Live Commit** | `5e84b74274e9ef413c40f836e64fddb920992f4f` (Schema V2 in last startup logs) |

---

## Stage A — Preflight Checklist

Complete every item before requesting deployment approval:

1. **Verify Merged Commit on `main`:**
   - Confirm the final stabilization PR (`chore/final-cloudforge-stabilization`) has been reviewed and merged into `main`.
   - Record the exact target `main` commit SHA.
2. **Verify GitHub Actions CI (`CloudForge CI`):**
   - Confirm all 4 CI jobs (`backend`, `frontend`, `worker` including disposable Kubernetes E2E, and `phase4` including Trivy/Cosign/monitoring) succeeded on the target `main` commit.
3. **Verify Render Service Settings (Dashboard → `cloudforge-staging` → Settings):**
   - Repository: `Itzx-kush/CloudForge`
   - Branch: `main` (if previously pointed at `deploy/render-phase2-staging-20261123`, switch branch to `main` only at release time)
   - Dockerfile Path: `./Dockerfile.render`
   - Auto-Deploy: `Off`
   - Region: Singapore
4. **Verify Existing Live Deployment Status:**
   - Check `https://cloudforge-staging.onrender.com/api/health` and inspect Render Logs to confirm current health and current applied Flyway schema version (previously reported as `Current version of schema "public": 2`).
5. **Verify Database Health & Pre-Upgrade Backup (CRITICAL):**
   - Confirm `cloudforge-staging-db` status is **Available** in Render Dashboard.
   - **Take a logical backup before applying V3–V11:**
     - *If authorized external access is available from your operator IP (temporarily permitted via Render DB Access Control ONLY with explicit owner authorization; never open `0.0.0.0/0`):*
       ```bash
       pg_dump "$RENDER_EXTERNAL_DATABASE_URL" \
         --format=custom --no-owner --no-privileges \
         --file="cloudforge-staging-pre-v11-$(date +%Y%m%d-%H%M%S).dump"
       ```
     - *If direct external DB access is blocked by IP allowlist restrictions (recommended default):* Either use Render Dashboard's backup/snapshot capability (if available on plan) or temporarily allowlist strictly your single operator `/32` IP for the duration of `pg_dump`, capture the dump, verify it locally via `pg_restore` into a disposable PostgreSQL 17 container (`docker run --rm -e POSTGRES_PASSWORD=test postgres:17-alpine`), and immediately remove the temporary `/32` rule afterward.
     - **Never** run `pg_restore` against `cloudforge-staging-db` without explicit operator authorization and incident-recovery review.
6. **Confirm V2-to-V11 Migration Safety:**
   - Verified locally on disposable PostgreSQL 17 (`docs/FINAL_INTEGRATION_TEST_REPORT.md`): authentic Flyway V2 database seeded across all 9 V1–V2 tables upgraded cleanly to V11 (37 tables) with 100% existing row preservation, Hibernate `ddl-auto=validate` success, repeatability on restart (`No migration necessary`), and `pg_restore` verification.
7. **Verify Required Render Environment Variables:**
   - Confirm **Internal** database variables are set: `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`.
   - Confirm same-origin security variables:
     - `SPRING_PROFILES_ACTIVE=render`
     - `SPRING_FLYWAY_ENABLED=true`
     - `CLOUDFORGE_COOKIE_SECURE=true`
     - `CLOUDFORGE_COOKIE_SAME_SITE=lax`
     - `CLOUDFORGE_FRONTEND_BASE_URL=https://cloudforge-staging.onrender.com`
     - `CLOUDFORGE_ALLOWED_REDIRECT_ORIGINS=https://cloudforge-staging.onrender.com`
     - `CLOUDFORGE_CORS_ALLOWED_ORIGINS=https://cloudforge-staging.onrender.com`
     - `GITHUB_OAUTH_REDIRECT_URI={baseUrl}/login/oauth2/code/{registrationId}`
     - `GITHUB_APP_CALLBACK_URL=https://cloudforge-staging.onrender.com/api/github/app/callback`
   - Confirm hosted GitHub OAuth & GitHub App secrets (`GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`, `GITHUB_APP_ID`, `GITHUB_APP_SLUG`, `GITHUB_APP_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`), and ensure `GITHUB_APP_PRIVATE_KEY_PATH` is **unset**.
   - Confirm hosted GitHub App has `Metadata: Read-only` + `Actions: Read-only` permissions approved on installed accounts (required for Phase 3.1 Pipelines sync).
   - Confirm **NO** worker/OCI/Kubernetes execution variables are enabled (`application-render.yml` locks them `false` even if accidentally set).
8. **Verify Application Docker Image Locally:**
   - Confirm `Dockerfile.render` built and started cleanly inside a `--memory=512m` container (`docs/FINAL_INTEGRATION_TEST_REPORT.md`).
9. **Confirm Zero Unexpected Infrastructure Changes:**
   - Same service ID (`srv-dc24cj6q1p3s73bo4ekg`), same URL (`https://cloudforge-staging.onrender.com`), same DB (`dpg-dc24bu56e07s73etkms0-a`), same region (Singapore), $0 added paid resources.

---

## Stage B — Mandatory Approval Checkpoint

**STOP HERE.**

Before executing Stage C, obtain explicit project-owner approval confirming:
- Target `main` commit SHA is approved.
- Pre-upgrade database backup (or accepted free-tier staging risk) is confirmed.
- Live deployment to `cloudforge-staging` is explicitly authorized.

---

## Stage C — Controlled Deployment (After User Approval Only)

1. Open https://dashboard.render.com/web/srv-dc24cj6q1p3s73bo4ekg.
2. Confirm branch is `main` and Dockerfile is `./Dockerfile.render`.
3. Click **Manual Deploy → Deploy latest commit** (or **Clear build cache & deploy** if needed to pick up Node 24 / Spring Boot 3.5.16 cleanly).
4. Monitor **Build Logs** (Vite production build + Maven package) and **Deploy Logs** (Spring Boot startup + Flyway V3–V11 migration lines).
5. Wait until Render reports deploy status **`Live`**.

---

## Stage D — Post-Deployment Verification

Run every check immediately after the service transitions to **`Live`**:

1. **Deployed Commit SHA:** Confirm Render deploy header matches the approved `main` commit SHA.
2. **Migration Confirmation (Render Logs):** Confirm Flyway logs report:
   - `Successfully validated 11 migrations`
   - `Migrating schema "public" to version "3 - github actions workflows"` through `"11 - security evidence"` (or `up to date` on subsequent restarts).
   - Zero Flyway validation or checksum errors.
3. **Health & Metadata Probe:**
   ```bash
   curl -i https://cloudforge-staging.onrender.com/api/health
   ```
   Verify HTTP `200`, `"status": "UP"`, `"version": "1.0.0"`, `"database": { "status": "UP", "engine": "PostgreSQL 17..." }`.
4. **SPA & Deep-Link Checks:**
   - Open `/`, `/login`, `/projects`, `/repositories`, `/pipelines`, `/deployments`, `/monitoring`, `/settings` directly and refresh each page — verify React loads without 404 and response includes `Cache-Control: no-cache, no-store, must-revalidate`.
5. **Security Boundary Checks:**
   - `GET /api/workspaces` (unauthenticated) → HTTP `401`.
   - `GET /actuator/prometheus` → HTTP `403` or `404` (never public).
   - `POST /api/internal/workers/register` → HTTP `503` (workers locked off).
6. **Authenticated Functional Checks:**
   - Sign in with GitHub OAuth → verify `CLOUDFORGE_SESSION` cookie (`HttpOnly; Secure; SameSite=Lax`) and `XSRF-TOKEN`.
   - Verify existing workspaces, projects, repositories, and audit history survived untouched.
   - Verify **Pipelines → GitHub Actions** syncs workflows (requires `Actions: Read` approved on hosted GitHub App).
   - Verify **Pipelines → CloudForge Pipelines**, **Builds & Artifacts**, **Deployments**, and **Monitoring** honestly display **Execution Plane Disabled** / **Unavailable** without fake builds or deployments.

---

## Stage E — Failure Analysis & Non-Destructive Recovery

If deployment fails at any stage:

1. **Build Failure (Docker/Maven/npm):**
   - Inspect Render build logs. Existing live container continues serving traffic untouched; database is unaffected.
2. **Startup / Memory Failure (OOM or Health Timeout):**
   - Inspect Render runtime logs for `OutOfMemoryError` or HikariCP timeout (`connection-timeout: 30000`). If JVM heap needs tightening inside 512 MiB, adjust `-XX:MaxRAMPercentage` (e.g., `50.0`) via a new commit rather than weakening security flags.
3. **Flyway Migration Failure:**
   - **STOP.** Do **not** run `flyway clean`, do **not** drop tables, do **not** disable `validate-on-migrate`, and do **not** edit applied migration SQL files.
   - Inspect the exact failing migration version and PostgreSQL error message in Render logs.
   - Remember: PostgreSQL executes DDL inside transactions per migration file, so a failed Flyway migration rolls back that single migration cleanly; inspect `flyway_schema_history` before fixing forward with a new corrective migration if needed.
4. **Application Rollback Compatibility Caveat:**
   - **Do NOT assume rolling back Render to commit `5e84b74` (Phase 2) reverses database migrations V3–V11.**
   - V3–V11 are strictly additive to V1–V2 tables (they do not drop or rename V1–V2 columns). Because Phase 2 (`5e84b74`) ran with `ddl-auto: none` and `flyway.validate-on-migrate: true`, rolling back to `5e84b74` **will fail Flyway validation** ("Detected applied migration not resolved locally: 3..11") unless the database is restored from the pre-upgrade V2 `pg_dump` backup into a clean target or a forward-fix commit is deployed. **Always prefer diagnosing and deploying a forward fix over blindly rolling back application code after migrations have run.**

---

## Database Expiration Continuity Plan (December 23, 2026)

Render's free PostgreSQL instance (`cloudforge-staging-db`) is scheduled to expire on **December 23, 2026**.

Before **December 16, 2026** (at least 7 days before expiration):
1. Export a full logical backup (`pg_dump --format=custom --no-owner --no-privileges`) and verify restore locally into a disposable `postgres:17-alpine` container.
2. Choose one non-destructive continuity path (requires explicit owner decision — do not create paid resources automatically):
   - **Option 1 (Paid Render PostgreSQL Basic):** Upgrade `cloudforge-staging-db` in Render Dashboard or restore the verified `pg_dump` into a persistent paid PostgreSQL 17 instance and update `POSTGRES_*` variables.
   - **Option 2 (External Managed PostgreSQL 17):** Provision a persistent PostgreSQL 17 database (e.g., Neon/Supabase/RDS with TLS `sslmode=require`), restore the verified `pg_dump`, update Render's `POSTGRES_*` environment variables, and verify `/api/health` + `flyway_schema_history`.
   - **Option 3 (Capstone Archive):** Archive the verified `.dump` offline if staging is no longer needed after academic evaluation.
