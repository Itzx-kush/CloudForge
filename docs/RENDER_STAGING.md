# CloudForge Phase 2 — Render staging handoff

CloudForge is hosted as **one HTTPS origin**. The Render Docker service compiles React and bundles the Vite output into Spring Boot's static resources. Spring Boot serves the SPA and /api, /oauth2, /login/oauth2 and GitHub App callbacks on the same origin, preserving cookie sessions and CSRF behavior without a production Vite proxy.

## Hosted resources

- Web service: https://dashboard.render.com/web/srv-dc24cj6q1p3s73bo4ekg
- Public origin: https://cloudforge-staging.onrender.com
- PostgreSQL 17: https://dashboard.render.com/d/dpg-dc24bu56e07s73etkms0-a
- Database and web region: Singapore
- Web service deploy branch: `deploy/render-phase2-staging-20261123` (auto deploy is OFF while validating; main remains unchanged)
- Database: fresh hosted database, no import from localhost
- Initial service plan: free; PostgreSQL free instance expires on December 23, 2026. **Do not store irreplaceable data without upgrading and arranging backups.**

## Required manual Render environment variables

In the web service **Environment** page, use the **Internal** connection details of the Render PostgreSQL database (not the external URL).

| Key | Value |
|---|---|
| `POSTGRES_HOST` | Internal PostgreSQL hostname from database's Connect details |
| `POSTGRES_PORT` | Internal PostgreSQL port (normally 5432; check Dashboard) |
| `POSTGRES_DB` | Database name shown in the Postgres Dashboard |
| `POSTGRES_USER` | Database username shown in the Postgres Dashboard |
| `POSTGRES_PASSWORD` | Database password from the Postgres Dashboard (secret) |
| `GITHUB_OAUTH_CLIENT_ID` | Hosted OAuth App Client ID |
| `GITHUB_OAUTH_CLIENT_SECRET` | Hosted OAuth App Client Secret (secret) |
| `GITHUB_APP_ID` | Hosted GitHub App numeric ID |
| `GITHUB_APP_SLUG` | Hosted GitHub App slug |
| `GITHUB_APP_PRIVATE_KEY` | Hosted GitHub App PEM private key, with actual newlines or literal \n sequences (secret) |
| `GITHUB_WEBHOOK_SECRET` | Hosted GitHub App webhook signing secret (secret) |

The hosted service already has `SPRING_PROFILES_ACTIVE=render`, `SPRING_FLYWAY_ENABLED=true`, `CLOUDFORGE_COOKIE_SECURE=true`, `CLOUDFORGE_COOKIE_SAME_SITE=lax`, `CLOUDFORGE_FRONTEND_BASE_URL=https://cloudforge-staging.onrender.com`, `CLOUDFORGE_ALLOWED_REDIRECT_ORIGINS=https://cloudforge-staging.onrender.com`, `CLOUDFORGE_CORS_ALLOWED_ORIGINS=https://cloudforge-staging.onrender.com`, `GITHUB_OAUTH_REDIRECT_URI={baseUrl}/login/oauth2/code/{registrationId}`, and `GITHUB_APP_CALLBACK_URL=https://cloudforge-staging.onrender.com/api/github/app/callback`.

**Do not set `GITHUB_APP_PRIVATE_KEY_PATH`** in Render if you supply the private key via `GITHUB_APP_PRIVATE_KEY`. Never put credentials in source control.

## Create SEPARATE hosted GitHub integrations

GitHub Developer Settings: https://github.com/settings/developers

- OAuth App Homepage: `https://cloudforge-staging.onrender.com`
- OAuth App Authorization callback: `https://cloudforge-staging.onrender.com/login/oauth2/code/github`
- GitHub App Homepage: `https://cloudforge-staging.onrender.com`
- GitHub App Setup URL: `https://cloudforge-staging.onrender.com/api/github/app/callback` (enable **Redirect on update**)
- GitHub App Webhook URL: `https://cloudforge-staging.onrender.com/api/webhooks/github`
- GitHub App permissions: Metadata read only, as defined in docs/GITHUB_INTEGRATION.md
- GitHub App subscriptions: Installation, Installation repositories, Repository.
- Install the **hosted** App on the required repositories only after web credentials are present and backend healthy.

Preserve the local GitHub OAuth App and GitHub App; their localhost URLs should remain unchanged.

## Validate staging

1. Set PostgreSQL variables in the Render web service Environment page and choose **Save, rebuild and deploy** (or the Dashboard's equivalent deploy option).
2. Confirm the web deploy is **Live**, then open `https://cloudforge-staging.onrender.com/api/health`: HTTP 200 with backend and database UP.
3. Confirm the homepage displays React and direct navigation/reload to `/login` and `/projects` serves the SPA.
4. Configure hosted OAuth credentials and deploy; sign in with GitHub, verify HttpOnly Secure session cookie and CSRF behavior.
5. Configure and install hosted GitHub App; verify installation redirect, repository discovery and sync.
6. Send live GitHub webhook events; verify GitHub delivery HTTP status and Render logs, signature validation and idempotency.
7. Verify session logout, Flyway migrations, database persistence after redeploy, and audit events.
8. Enable automatic deploys from an approved branch only after reviewing GitHub workflow policy.

Staging uses new, empty PostgreSQL, not local data. Do not copy local session records or local GitHub installation IDs.
