# CloudForge GitHub Integration Setup Guide

CloudForge separates **user authentication** (**GitHub OAuth App**) from **repository discovery, Actions telemetry, webhook synchronization, and opt-in source acquisition** (**GitHub App**). User sign-in requests only identity scopes (`read:user`, `user:email`), while repository access uses short-lived, installation-scoped GitHub App tokens and HMAC-SHA256 verified webhooks.

> **Security Note:** Never commit `.env`, `.pem` private key files, OAuth client secrets, or webhook secrets to Git. `.gitignore` excludes `.env`, `.env.*` (except `.env.example`), `*.pem`, and `*.key`.

---

## 1. Required Environment Variables

Configure these values in your local ignored `.env` (or Render **Environment** for staging):

| Variable | Purpose | Required For |
| --- | --- | --- |
| `GITHUB_OAUTH_CLIENT_ID` | GitHub OAuth App Client ID | User sign-in via GitHub |
| `GITHUB_OAUTH_CLIENT_SECRET` | GitHub OAuth App Client Secret | User sign-in via GitHub |
| `GITHUB_OAUTH_REDIRECT_URI` | OAuth callback template (default `{baseUrl}/login/oauth2/code/{registrationId}`) | User sign-in via GitHub |
| `GITHUB_APP_ID` | Numeric GitHub App ID | GitHub App RS256 JWT signing |
| `GITHUB_APP_SLUG` | URL slug of your GitHub App (`https://github.com/apps/<slug>`) | Installation URL generation |
| `GITHUB_APP_PRIVATE_KEY` | RSA PEM private key (multiline or `\n`-escaped single line) | GitHub App JWT signing (Option A — required on Render) |
| `GITHUB_APP_PRIVATE_KEY_PATH` | Path to a local `.pem` file outside Git | GitHub App JWT signing (Option B — local dev only) |
| `GITHUB_WEBHOOK_SECRET` | Shared HMAC-SHA256 secret configured on the GitHub App | Verifying `POST /api/webhooks/github` |
| `GITHUB_APP_CALLBACK_URL` | Post-installation Setup URL (`http://localhost:5173/api/github/app/callback` locally or `https://cloudforge-staging.onrender.com/api/github/app/callback` on Render) | GitHub App installation callback |
| `CLOUDFORGE_FRONTEND_BASE_URL` | Frontend base origin | Safe post-login / callback redirects |
| `CLOUDFORGE_ALLOWED_REDIRECT_ORIGINS` | Comma-separated allowlist of permitted redirect origins | Open-redirect prevention |
| `CLOUDFORGE_COOKIE_SECURE` | `false` for local HTTP (`localhost`), **`true` in HTTPS staging/production** | Cookie security |

---

## 2. Creating a GitHub OAuth App (User Sign-In)

Create **separate** OAuth Apps for local development and Render staging:

| Setting | Local Development | Render Staging |
| --- | --- | --- |
| **Homepage URL** | `http://localhost:5173` | `https://cloudforge-staging.onrender.com` |
| **Authorization callback URL** | `http://localhost:5173/login/oauth2/code/github` | `https://cloudforge-staging.onrender.com/login/oauth2/code/github` |

CloudForge intentionally requests only `read:user` and `user:email` — never `repo` or `admin:org`. OAuth access tokens never leave the backend server session.

---

## 3. Creating a GitHub App (Least-Privilege Repository Access)

Create **separate** GitHub Apps for local development and Render staging:

| Setting | Local Development | Render Staging |
| --- | --- | --- |
| **Homepage URL** | `http://localhost:5173` | `https://cloudforge-staging.onrender.com` |
| **Setup URL** (enable *Redirect on update*) | `http://localhost:5173/api/github/app/callback` | `https://cloudforge-staging.onrender.com/api/github/app/callback` |
| **Webhook URL** | Optional tunnel (`https://<tunnel>/api/webhooks/github`) | `https://cloudforge-staging.onrender.com/api/webhooks/github` |

### Repository Permissions (Least Privilege)
- **Metadata:** `Read-only` *(mandatory — repository discovery and sync)*
- **Actions:** `Read-only` *(required for Phase 3.1 read-only workflow, run, and job telemetry on `/pipelines` — never grant `Read and write`)*
- **Contents:** `No access` for Render staging and read-only setups; set `Read-only` **only** on local/trusted worker setups that explicitly enable Phase 3.5 pinned source acquisition (`CLOUDFORGE_SOURCE_ACQUISITION_ENABLED=true` + repository/worker allowlists).
- Leave **Workflows**, **Administration**, **Deployments**, **Issues**, and **Pull requests** as **No access**.

> *Upgrading an existing GitHub App:* After adding `Actions: Read-only`, approve the updated permissions on each installation under **GitHub → Settings → Applications → Installed GitHub Apps**.

### Webhook Event Subscriptions
- `Meta` / `Ping` (automatic)
- `Installation` (suspension, unsuspension, deletion)
- `Installation repositories` (repositories added/removed)
- `Repository` (renames, visibility, archive, deletion)

Unsupported events (such as `push`) return `202 Accepted` safely without triggering unapproved builds.

---

## 4. Security Boundaries & Known Limitations

- **Personal vs. Organization Installations:** Personal (`User`) installations enforce strict matching GitHub user ID ownership on callback. Attaching **new** unverified `Organization` installations fails closed (`403 Forbidden`) until user-to-server installation verification (`GET /user/installations`) is added. Already-linked workspace installations remain refreshable by workspace `OWNER`s.
- **Plaintext vs. Encrypted Secrets:** Project non-secret environment variables actively reject secret-like keys and credential values; sensitive credentials belong in CloudForge's Phase 3.4 AES-256-GCM **Encrypted Environment Secrets** vault (`docs/PHASE3_4_SECRET_MANAGEMENT.md`).
- **Live Credentials Required for External GitHub Calls:** Automated test suites verify OAuth/CSRF, RS256 App JWT signing, installation callbacks, Actions sync, and HMAC-SHA256 webhooks using real crypto and controlled HTTP stubs; live calls against `github.com` require configuring your own GitHub OAuth App and GitHub App credentials.
