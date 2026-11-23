# CloudForge GitHub Integration Setup Guide (Phase 2)

CloudForge separates **user authentication** (**GitHub OAuth App**) from **repository discovery and lifecycle synchronization** (**GitHub App**). This separation enforces least-privilege access: user sign-in only asks for identity scopes (`read:user`, `user:email`), while repository metadata access uses short-lived, installation-scoped GitHub App tokens and HMAC-SHA256 verified webhooks.

> **Important Security Note:** Never commit `.env`, `.pem` private key files, OAuth client secrets, or webhook secrets to Git. `.gitignore` already excludes `.env`, `.env.*` (except `.env.example`), `*.pem`, and `*.key`.

---

## 1. Required Environment Variables

Add these values to your local ignored `.env` file (copied from `.env.example`):

| Variable | Purpose | Required For |
| --- | --- | --- |
| `GITHUB_OAUTH_CLIENT_ID` | GitHub OAuth App Client ID | User sign-in via GitHub |
| `GITHUB_OAUTH_CLIENT_SECRET` | GitHub OAuth App Client Secret | User sign-in via GitHub |
| `GITHUB_OAUTH_REDIRECT_URI` | OAuth callback template (default `{baseUrl}/login/oauth2/code/{registrationId}`) | User sign-in via GitHub |
| `GITHUB_APP_ID` | Numeric GitHub App ID | GitHub App JWT signing |
| `GITHUB_APP_SLUG` | URL slug of your GitHub App (from `https://github.com/apps/<slug>`) | Installation URL generation |
| `GITHUB_APP_PRIVATE_KEY` | RSA PEM private key (multiline or `\n`-escaped single line) | GitHub App JWT signing (option A) |
| `GITHUB_APP_PRIVATE_KEY_PATH` | Path to a mounted/local `.pem` file outside Git | GitHub App JWT signing (option B) |
| `GITHUB_WEBHOOK_SECRET` | Shared HMAC-SHA256 secret configured on the GitHub App | Verifying `POST /api/webhooks/github` |
| `GITHUB_APP_CALLBACK_URL` | Post-installation Setup URL (`http://localhost:5173/api/github/app/callback`) | GitHub App installation callback |
| `CLOUDFORGE_FRONTEND_BASE_URL` | Frontend base origin (`http://localhost:5173`) | Safe post-login / callback redirects |
| `CLOUDFORGE_ALLOWED_REDIRECT_ORIGINS` | Comma-separated allowlist of permitted redirect origins | Open-redirect prevention |
| `CLOUDFORGE_COOKIE_SECURE` | Set `false` for local HTTP (`localhost`), **`true` in HTTPS production** | Cookie security |

---

## 2. Creating a GitHub OAuth App (User Sign-In)

1. Open **GitHub → Settings → Developer settings → OAuth Apps → New OAuth App**.
2. Fill in the application settings for local development:
   - **Application name:** `CloudForge Local Dev`
   - **Homepage URL:** `http://localhost:5173`
   - **Authorization callback URL:**
     ```text
     http://localhost:5173/login/oauth2/code/github
     ```
     *(Vite proxies `/oauth2/*` and `/login/oauth2/*` to Spring Boot on `:8080` with `X-Forwarded-*` headers so session cookies remain strictly same-origin on `localhost:5173`.)*
3. Click **Register application**.
4. Copy the **Client ID** into `GITHUB_OAUTH_CLIENT_ID` in `.env`.
5. Click **Generate a new client secret** and copy the secret into `GITHUB_OAUTH_CLIENT_SECRET` in `.env`.

### OAuth Scopes Requested by CloudForge

CloudForge intentionally requests only:
- `read:user`
- `user:email`

CloudForge does **not** request `repo` or `admin:org` OAuth scopes. GitHub OAuth tokens are never returned to browser JavaScript, never stored in client storage, and never logged.

---

## 3. Creating a GitHub App (Least-Privilege Repository Access)

1. Open **GitHub → Settings → Developer settings → GitHub Apps → New GitHub App**.
2. Configure basic identity & URLs:
   - **GitHub App name:** Choose a unique name (e.g., `cloudforge-local-<yourhandle>`). Note the URL slug created by GitHub (e.g., `cloudforge-local-yourhandle`) for `GITHUB_APP_SLUG`.
   - **Homepage URL:** `http://localhost:5173`
   - **Setup URL (optional):**
     ```text
     http://localhost:5173/api/github/app/callback
     ```
   - Check **"Redirect on update"** if you want repository scope updates on GitHub to return to CloudForge.
3. Configure **Webhook**:
   - **Active:** Checked (if testing live webhook delivery via a tunnel; optional for local-only pull sync).
   - **Webhook URL:** `https://<your-public-tunnel-or-domain>/api/webhooks/github`
   - **Webhook secret:** Generate a strong random secret (e.g., `openssl rand -hex 32`) and save the exact value in `GITHUB_WEBHOOK_SECRET` in `.env`.
4. Configure **Least-Privilege Repository Permissions** (Phase 2):
   - **Metadata:** `Read-only` *(mandatory, automatically enabled — used to discover and synchronize authorized repositories)*
   - Leave **Contents**, **Administration**, **Actions**, **Deployments**, **Issues**, and **Pull requests** as **No access** for Phase 2. CloudForge does not clone, build, or execute repository code in Phase 2.
5. Subscribe to **Phase 2 Lifecycle Webhook Events**:
   - `Meta` / `Ping` (automatic)
   - `Installation` (tracks app suspension, unsuspension, and deletion)
   - `Installation repositories` (tracks repositories added/removed from the installation)
   - `Repository` (tracks renames, visibility changes, archiving, and deletions)
6. Under **Where can this GitHub App be installed?**, choose **Only on this account** for local development (or **Any account** for multi-tenant teams).
7. Click **Create GitHub App**.
8. Copy the numeric **App ID** into `GITHUB_APP_ID` in `.env`.
9. Scroll to **Private keys** → click **Generate a private key**. A `.pem` file will download.
   - Store the `.pem` file outside Git (or in a Git-ignored path) and either:
     - Mount/point `GITHUB_APP_PRIVATE_KEY_PATH` to that file, **or**
     - Encode newlines as `\n` and assign it to `GITHUB_APP_PRIVATE_KEY` in `.env`.

---

## 4. Local Development & Testing Workflow

### Start PostgreSQL 17, Spring Boot Backend & Vite Frontend

```powershell
# 1. Start PostgreSQL 17 and rebuild/start the backend (runs Flyway V1 & V2 automatically)
docker compose up -d --build

# 2. Verify backend & PostgreSQL health
Invoke-RestMethod http://localhost:8080/api/health

# 3. Start the React + TypeScript frontend
cd frontend
npm run dev
```

### Testing Authentication Locally (Requires Real OAuth Credentials)

1. Open `http://localhost:5173`. Unauthenticated visitors are redirected to `/login`.
2. Verify that if `GITHUB_OAUTH_CLIENT_ID` is empty, the UI cleanly displays **GitHub OAuth Setup Required** without fabricating login state.
3. Once `GITHUB_OAUTH_CLIENT_ID` and `GITHUB_OAUTH_CLIENT_SECRET` are set in `.env` and Docker Compose is restarted (`docker compose up -d`), click **Continue with GitHub**.
4. Complete GitHub authorization; verify redirect back to `http://localhost:5173/`, avatar/username display in header, personal workspace creation in PostgreSQL, and `USER_REGISTERED` / `USER_LOGIN` entry in **Settings → Audit History**.
5. Click **Sign out** and confirm the session terminates (`CLOUDFORGE_SESSION` cookie cleared, `/api/auth/me` returns `401`).

### Testing GitHub App Repository Integration Locally (Requires Real GitHub App Credentials)

1. Sign in as a workspace **OWNER** and navigate to **Repositories**.
2. Click **Connect GitHub App**, select one or more repositories on GitHub, and authorize installation.
3. GitHub redirects back to `/api/github/app/callback?installation_id=...&state=...`; CloudForge verifies the single-use session state, validates installation ownership, stores the installation record, requests a short-lived installation token (`metadata: read`), and synchronizes authorized repositories.
4. Search/filter repositories and use **Link to Project** to bind a repository to a persistent CloudForge project.

### Testing GitHub Webhooks Locally

Because `localhost:5173` / `127.0.0.1:8080` is not publicly reachable from GitHub's servers:
- **Automated Verification (Included):** `GitHubWebhookIntegrationTest` verifies real `HmacSHA256` signatures over raw bytes, constant-time comparison, rejection of missing/invalid signatures (`401`), duplicate delivery idempotency (`200 duplicate`), installation/repository lifecycle synchronization, and safe ignoring of unsupported events like `push` (`202 ignored`).
- **Optional Live Tunnel Verification:** If you want to test live GitHub webhook delivery against localhost, expose port `8080` temporarily with an authenticated developer tunnel (such as `gh webhook forward` or `smee.io`) pointing to `http://127.0.0.1:8080/api/webhooks/github` using your matching `GITHUB_WEBHOOK_SECRET`. Never expose PostgreSQL (`5432`) publicly.

---

## 5. Security Considerations & Known Limitations

- **Verified vs. External Configuration:**
  - All backend authentication flows, session invalidation, CSRF protection, Flyway migrations, RBAC rules, cross-workspace isolation, GitHub App JWT/token handling, ownership validation, repository syncing, and HMAC-SHA256 webhook verification are **implemented and verified with automated tests** (using mocked GitHub HTTP responses where external GitHub credentials are absent).
  - End-to-end live sign-in and live GitHub App installation against `github.com` require registering your own GitHub OAuth App and GitHub App credentials in `.env` and have **not** been claimed as live-tested without real credentials.
- **No Plaintext Secret Manager in Phase 2:** Project environment configuration intentionally supports **non-secret variables only** and actively rejects secret-like keys (`*SECRET*`, `*PASSWORD*`, `*TOKEN*`, `*PRIVATE_KEY*`, `*API_KEY*`) and credential patterns (`ghp_*`, PEM blocks). Encrypted secret storage (KMS / External Secrets Manager) is deferred to Phases 3–4.
- **No Code Execution, Builds, or Deployments in Phase 2:** Neither repository linking, environment updates, nor GitHub webhook deliveries execute repository code or trigger deployments. Isolated rootless build workers and Kubernetes rollouts are deferred to Phase 3.
- **Organization Installation Ownership:** Personal (`User`) GitHub App installations enforce matching GitHub user ID / login ownership on callback. Organization installations require an authenticated CloudForge workspace `OWNER` with a valid single-use session state token; fine-grained GitHub organization admin membership checks can be expanded in a future phase if multi-organization SSO is introduced.
- **HTTPS Production Requirement:** For any non-localhost deployment, run behind TLS/HTTPS and set `CLOUDFORGE_COOKIE_SECURE=true`, update `CLOUDFORGE_FRONTEND_BASE_URL` and `CLOUDFORGE_ALLOWED_REDIRECT_ORIGINS`, and store secrets in a dedicated secret manager rather than `.env` files.
