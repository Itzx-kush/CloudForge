# CloudForge Capstone Project Demonstration Guide

This guide provides a clear, reproducible, honest demonstration plan for presenting **CloudForge (`v1.0.0`)** in an academic capstone evaluation.

> **Honesty Principle:** Never fabricate build successes, security scans, or Kubernetes deployments. Live demos must execute real services; where external credentials (such as live GitHub OAuth or a live cluster) are unavailable during a presentation, show verified local execution or clearly labeled recorded evidence from prior verified runs.

---

## 1. Presentation Structure (Recommended 15–20 Minutes)

### Part 1 — Architecture & Problem Statement (3 min)
- Present the system architecture diagram from [`docs/ARCHITECTURE.md`](ARCHITECTURE.md).
- Highlight the **key architectural decision**: strict **two-domain separation** between the Spring Boot Control Plane (`backend/`) and the isolated Execution Plane (`worker/target/cloudforge-worker.jar`), explaining why Render staging safely disables execution workers while local/cluster setups enable them.
- Explain **identity vs. repository separation** (GitHub OAuth `read:user`/`user:email` vs. GitHub App least-privilege `metadata: read`/`actions: read` + HMAC-SHA256 webhooks).

### Part 2 — Live Control Plane, Auth, Workspaces & Projects (4 min)
1. **Live Health Probe:** Show `GET /api/health` returning live PostgreSQL 17 JDBC latency and `version: "1.0.0"`, plus the **Dashboard** and **Monitoring** live probe inspector.
2. **Authentication & RBAC:** Sign in with GitHub OAuth (if configured in local `.env` or Render staging), show the `CLOUDFORGE_SESSION` `HttpOnly` cookie and `X-XSRF-TOKEN` CSRF protection, switch workspaces in the sidebar, and show **Settings** (Members, RBAC roles, Dark/Light/System theme & accent switching, Audit History).
3. **Repositories & Projects:** Show connected GitHub App installations & authorized repositories on `/repositories`, create/inspect a project on `/projects`, and demonstrate **Project Detail**:
   - Save a non-secret variable (e.g., `LOG_LEVEL=INFO`) in Development.
   - Show active plaintext secret rejection by attempting to save `API_SECRET=dummy` in non-secret config (`400 Bad Request`).
   - Show the **Encrypted Environment Secrets** panel (`AES-256-GCM` write-only metadata table, rotation, revocation).

### Part 3 — Pipelines, Workers, OCI Builds & Supply-Chain Evidence (5 min)
4. **GitHub Actions vs. CloudForge Pipelines (`/pipelines`):**
   - Show **GitHub Actions** tab (read-only synced workflows, runs, and live jobs/steps).
   - Switch to **CloudForge Pipelines** tab: create a declarative multi-stage DAG definition, show cycle/shell-command rejection, queue a run with `Idempotency-Key`, and show live worker pool telemetry.
5. **Real Isolated Worker Execution:**
   - Start a local host-side worker (`java -jar worker/target/cloudforge-worker.jar`) with Docker Desktop running.
   - Watch the worker claim the queued `ENV_VALIDATION` / `CONTROLLED_SMOKE_TEST` stage, execute inside a hardened `--network=none --read-only --cap-drop=ALL --user 65534:65534` Alpine container, stream sanitized execution logs, and transition the pipeline run to `SUCCEEDED`.
6. **Immutable OCI Builds & Registry Publishing (`Builds & Artifacts` tab):**
   - Demonstrate real BuildKit OCI layout generation and publishing to a disposable `registry:2` (or run `mvn -B -Dtest=RealBuildKitAndRegistryEndToEndTest test` live in ~9 seconds to print the independently verified `@sha256:` digest!).
7. **DevSecOps Security Evidence & Production Gate:**
   - Explain `infrastructure/supplychain/assess-image.sh` (Trivy CVE scan + CycloneDX SBOM + Cosign signing -> signed ECDSA envelope).
   - Show **Monitoring → Workspace operations → Signed artifact assessments** and explain how `ArtifactEvidenceService.requireProductionApproval` fails closed unless current (<= 24h) signed zero-high/critical evidence exists.

### Part 4 — Kubernetes Deployment, Release History & Verified Rollback (5 min)
8. **Disposable Kubernetes Setup:** Show `provision-local-namespace.ps1` + `rbac-verification.ps1` proving least-privilege namespace isolation (permitted `deployments`/`services`/`pods` vs. denied `secrets`/`clusterrolebindings`/`kube-system`).
9. **Deploy Revision 1 → Update to Revision 2 → Rollback to Revision 1:**
   - Show `/deployments` (Deployments, Clusters & Namespaces, Release History tabs).
   - Run `RealLocalKubernetesEndToEndTest` against disposable `rancher/k3s` (or drive through the UI with a running `KUBERNETES_DEPLOY` worker):
     - Deploy immutable Image A (`2/2` ready replicas, verified digest).
     - Update to immutable Image B (`2/2` ready replicas, new digest).
     - Compare releases, preview rollback (with `expectedOperationId` precondition), and roll back to Image A — verifying identical Deployment UID, restored Image A digest, `2/2` readiness, and read-only reconciliation observation (`applyInitiated=false`).
     - Show negative invalid-digest test failing cleanly with `K8S_IMAGE_PULL_FAILED` (never falsely marked `SUCCEEDED`).

### Part 5 — Observability, Security Controls & Honest Limitations (3 min)
10. **Monitoring & Telemetry:** Show `compose.monitoring.yaml`, `/actuator/prometheus` Bearer-token protection (`403` without token, `200` with token), `WorkerTelemetry` `cloudforge.prom`, 5 Grafana dashboards, and 8 Prometheus alert rules.
11. **Honest Assessment & Future Work:** Walk through the completion matrix in [`docs/FINAL_PROJECT_COMPLETION_REPORT.md`](FINAL_PROJECT_COMPLETION_REPORT.md) (distinguishing local demo, academic capstone, Render staging, trusted internal beta, and untrusted public production requirements).

---

## 2. Quick Pre-Demo Setup Checklist (Controlled Local Mode)

Run 10–15 minutes before the presentation:

```powershell
# 1. Start Docker Desktop and verify daemon
docker info --format "{{.ServerVersion}}"

# 2. Start PostgreSQL 17 + Spring Boot backend (preserves existing local volume)
docker compose up -d --build
Invoke-RestMethod http://localhost:8080/api/health

# 3. Start React frontend
cd frontend
npm run dev
# Open http://localhost:5173
```

### Offline / No-GitHub-Credentials Fallback Path
If campus Wi-Fi blocks GitHub OAuth or external container pulls during evaluation:
1. Show `/login` honestly displaying **GitHub OAuth Setup Required** with live API & PostgreSQL health badge (`UP`).
2. Run the automated verification suites locally (zero external credentials required):
   - Backend + H2/PostgreSQL tests: `cd backend && mvn -B test`
   - Worker real Docker isolation + real BuildKit + local `registry:2` (`FROM scratch` — works 100% offline!): `cd worker && mvn -B test`
   - Frontend Vitest interactive UI flow tests (38 tests): `cd frontend && npm test`
3. Present recorded evidence from [`docs/FINAL_INTEGRATION_TEST_REPORT.md`](FINAL_INTEGRATION_TEST_REPORT.md) and GitHub Actions CI logs, explicitly stating which outputs are live vs. recorded prior runs.

---

## 3. Key Technical Decisions to Highlight for Evaluators

1. **Why a Separate Worker Module (`worker/`) Instead of Running Docker Inside Spring Boot?** Prevents control-plane compromise from escalating to host root via `/var/run/docker.sock` and allows Render free-tier hosting with zero privileged execution.
2. **Why Immutable `@sha256:` Digests Instead of Mutable Tags (`:latest`)?** Guarantees the exact scanned/signed image bytes are deployed and restored on rollback.
3. **Why Lease Tokens + Monotonic Fencing Generations?** Prevents split-brain / zombie workers after crashes or network partitions from overwriting newer attempt or deployment state.
4. **Why `RECONCILIATION_REQUIRED` Instead of Automatic Blind Rollback on Mid-Apply Crash?** Avoids compounding partial Kubernetes writes when external cluster state is uncertain; requires explicit owner-triggered read-only observation first.
5. **Why Flyway Non-Destructive Migrations (`V1`–`V11`) + `ddl-auto: none`?** Preserves live staging data safely across phase upgrades (verified V2→V11 with 100% row survival).
