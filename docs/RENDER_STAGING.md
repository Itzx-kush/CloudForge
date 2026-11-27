# CloudForge — Render Staging Reference

CloudForge is hosted on Render as **one HTTPS origin**. [`Dockerfile.render`](../Dockerfile.render) builds the React 19 + TypeScript SPA with Node 24, bundles the Vite `dist/` output into Spring Boot's static resources, and packages a single non-root Java 17 runtime image. Spring Boot serves both the SPA and `/api`, `/oauth2`, `/login/oauth2`, and `/api/github/app/callback` on the same origin with explicit `Cache-Control: no-cache, no-store, must-revalidate` headers on HTML routes and immutable caching on `/assets/**`.

> **Release Procedure:** For the complete step-by-step preflight, backup, approval checkpoint, deployment, post-deploy verification, and recovery procedure to advance `cloudforge-staging` to the latest `main` release (`v1.0.0`, migrations `V1`–`V11`), follow [`docs/RENDER_FINAL_DEPLOYMENT.md`](RENDER_FINAL_DEPLOYMENT.md).

---

## 1. Existing Hosted Render Resources

| Resource | Details |
| --- | --- |
| **Web Service** | `cloudforge-staging` (`srv-dc24cj6q1p3s73bo4ekg`) — https://dashboard.render.com/web/srv-dc24cj6q1p3s73bo4ekg |
| **Public URL** | https://cloudforge-staging.onrender.com |
| **Repository & Branch** | `https://github.com/Itzx-kush/CloudForge` — `main` (Automatic deployment: **Disabled**) |
| **Runtime & Dockerfile** | Docker — `./Dockerfile.render` |
| **Region & Plan** | Singapore — Free (`512 MiB` RAM container; JVM tuned to `-XX:InitialRAMPercentage=20.0 -XX:MaxRAMPercentage=55.0`) |
| **PostgreSQL 17 Database** | `cloudforge-staging-db` (`dpg-dc24bu56e07s73etkms0-a`, DB name `cloudforge_staging_db`) — Singapore, Free plan |
| **Database Expiration** | **December 23, 2026** (Free Render PostgreSQL expiration — see continuity plan in [`docs/RENDER_FINAL_DEPLOYMENT.md`](RENDER_FINAL_DEPLOYMENT.md)) |

---

## 2. Render Safety Posture (`application-render.yml`)

When `SPRING_PROFILES_ACTIVE=render` is active, [`backend/src/main/resources/application-render.yml`](../backend/src/main/resources/application-render.yml) explicitly locks all privileged execution and local-dev bypass flags to `false`:

- `cloudforge.workers.enabled: false` and `cloudforge.workers.scheduler-enabled: false`
- `cloudforge.source.enabled: false`
- `cloudforge.secrets.worker-delivery-enabled: false` and `cloudforge.secrets.allow-insecure-local-delivery: false`
- `cloudforge.oci.enabled: false`, `allow-local-dev-registry: false`, `allow-insecure-local-credential-delivery: false`
- `cloudforge.kubernetes.enabled: false`, `allow-local-dev-clusters: false`, `allow-insecure-local-credential-delivery: false`, `allow-production-deployments: false`
- `management.tracing.enabled: false` (and `/actuator/prometheus` remains unexposed because the `monitoring` profile is not active)

The Render web service has no Docker daemon, no BuildKit socket, no `kubectl`, and no kubeconfig access. On Render, the UI displays honest **Execution Plane Disabled** / **Unavailable** states for worker-dependent actions rather than fabricating builds or deployments.

---

## 3. Required Render Environment Variables

Use the **Internal** connection details from `cloudforge-staging-db` (never the public external URL, and never widen the database IP allowlist unnecessarily):

| Key | Purpose |
| --- | --- |
| `POSTGRES_HOST` | Internal PostgreSQL hostname from Render DB Connect details |
| `POSTGRES_PORT` | Internal PostgreSQL port (`5432`) |
| `POSTGRES_DB` | `cloudforge_staging_db` |
| `POSTGRES_USER` | Database username from Render DB Dashboard |
| `POSTGRES_PASSWORD` | Database password from Render DB Dashboard (secret) |
| `GITHUB_OAUTH_CLIENT_ID` | Hosted GitHub OAuth App Client ID |
| `GITHUB_OAUTH_CLIENT_SECRET` | Hosted GitHub OAuth App Client Secret (secret) |
| `GITHUB_APP_ID` | Hosted GitHub App numeric ID |
| `GITHUB_APP_SLUG` | Hosted GitHub App slug |
| `GITHUB_APP_PRIVATE_KEY` | Hosted GitHub App PEM private key (secret; do **not** set `GITHUB_APP_PRIVATE_KEY_PATH`) |
| `GITHUB_WEBHOOK_SECRET` | Hosted GitHub App HMAC-SHA256 webhook secret (secret) |
| `CLOUDFORGE_SECRETS_ENABLED` | Optional (`false` by default). Set `true` together with `CLOUDFORGE_SECRETS_ACTIVE_KEY_ID` and `CLOUDFORGE_SECRETS_KEYRING` only if encrypted secret storage in the UI is desired on staging (worker secret delivery stays locked `false`). |
| `CLOUDFORGE_SUPPLYCHAIN_PUBLIC_KEY` | Optional. ECDSA public key (raw Base64 DER or Cosign PEM) if accepting signed security assessments on staging. |

Standard Render profile variables already configured on the service:
- `SPRING_PROFILES_ACTIVE=render`
- `SPRING_FLYWAY_ENABLED=true`
- `CLOUDFORGE_COOKIE_SECURE=true`
- `CLOUDFORGE_COOKIE_SAME_SITE=lax`
- `CLOUDFORGE_FRONTEND_BASE_URL=https://cloudforge-staging.onrender.com`
- `CLOUDFORGE_ALLOWED_REDIRECT_ORIGINS=https://cloudforge-staging.onrender.com`
- `CLOUDFORGE_CORS_ALLOWED_ORIGINS=https://cloudforge-staging.onrender.com`
- `GITHUB_OAUTH_REDIRECT_URI={baseUrl}/login/oauth2/code/{registrationId}`
- `GITHUB_APP_CALLBACK_URL=https://cloudforge-staging.onrender.com/api/github/app/callback`

---

## 4. Hosted GitHub App & OAuth App Settings

Keep separate GitHub OAuth App and GitHub App registrations for Render staging (preserving your local `localhost:5173` apps):

- **OAuth App Homepage:** `https://cloudforge-staging.onrender.com`
- **OAuth App Callback URL:** `https://cloudforge-staging.onrender.com/login/oauth2/code/github`
- **GitHub App Homepage:** `https://cloudforge-staging.onrender.com`
- **GitHub App Setup URL:** `https://cloudforge-staging.onrender.com/api/github/app/callback` (enable **Redirect on update**)
- **GitHub App Webhook URL:** `https://cloudforge-staging.onrender.com/api/webhooks/github`
- **GitHub App Permissions:** `Metadata: Read-only` (required) + `Actions: Read-only` (required for Phase 3.1 GitHub Actions tab; approve permission update on existing installations).
- **GitHub App Subscriptions:** `Installation`, `Installation repositories`, `Repository`.
