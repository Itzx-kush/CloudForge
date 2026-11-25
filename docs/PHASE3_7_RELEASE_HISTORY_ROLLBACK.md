# Release history, rollback, and recovery

## Release records

The existing Kubernetes deployment ledger holds the immutable intent and terminal outcome. V10 adds revision numbers, the operation kind, and references to the restored release, preview precondition, and verified workload observation. Failed and cancelled mutations retain their revision numbers. Rollback consumes a new number and has its own operation, stage, lease, actor, outcome, and lifecycle events. A reconciliation observation has an operation ID and its own evidence, outside the release-number sequence.

Revision scope is the cluster connection, approved namespace name, and Deployment name. The namespace binding identifies workspace, project, and environment ownership. A cluster row lock serializes allocation and conflict checks for direct requests and pipeline stages. PostgreSQL enforces uniqueness on the target and revision. A workspace lock serializes requests that share a workspace-scoped idempotency key, and the fingerprint binds operation kind, target, expected predecessor, configuration, artifact, approval, and reason.

V10 backfills numbers in creation-time and ID order. Recorded operation IDs, timestamps, status, configuration and artifact references are preserved. The migration protects retained deployment artifacts and leaves validation evidence as recorded. A historical success with insufficient readiness evidence remains visible. Legacy pipeline snapshots that omitted a configured startup probe fail checksum verification when reconstructed.

## Rollback workflow

1. Inspect a release in **Deployments → Release History**. Compare it with another revision for the same workload.
2. Check eligibility. The preview shows the current recorded workload and expected predecessor, the target digest, differences, operational risks and any applicable approval.
3. Confirm the rollback and supply a reason. The API accepts an idempotency key and the preview's expected operation ID. A different latest operation invalidates the request. The worker independently checks live resource identities, current Deployment generation and immutable image before writing.
4. Follow the new rollback operation through the worker queue and deployment timeline. A verified result requires the exact requested image, observed generation, updated replicas, ready replicas, available replicas and healthy Deployment conditions.

A rollback restores a declarative snapshot using the current configuration validator, namespace bindings and manifest generator. The regenerated canonical checksum must match the historical checksum. The worker verifies the same checksum and workload policy. Its execution envelope prefixes the checksum with the mode so a pre-Phase-3.7 worker fails closed. The worker adds resourceVersion and UID preconditions to existing resources after canonical verification. A unique rollback annotation and imagePullPolicy set to Always force a fresh rollout and registry resolution of the digest using the existing Kubernetes pull configuration. Registry reachability is verified during rollout. Image-pull failures retain diagnostics and the original release history.

The worker rechecks the lease before both writes and uses Server-Side Apply with force disabled. Deployment and Service writes are separate Kubernetes operations. Cancellation, lease loss, conflicts, or failures after apply can leave uncertain external state.

## Reconciliation

An owner can queue verification of the affected release or a known candidate release. The worker reads the managed Deployment, Service and Pods, compares approved fields, and evaluates the live rollout. A successful observation establishes the current workload for subsequent requests. A failed observation retains the operator-intervention requirement. The original terminal outcome and its timeline remain historical evidence.

The worker verifies a selected managed state. Operators can compare candidates and inspect cluster diagnostics when the live workload does not match a known healthy release.

## API and policy

| Method and path beneath /api/workspaces/{workspaceId} | Purpose |
| --- | --- |
| GET /releases | Page release operations with project, environment, cluster, Deployment, status and kind filters |
| GET /releases/{id} | View release metadata and observed verification evidence |
| GET /releases/compare?first={id}&second={id} | Compare immutable snapshots for one target |
| GET /releases/{id}/rollback-eligibility | Evaluate policy and recorded evidence |
| POST /releases/{id}/rollback-preview | Read-only preview from recorded evidence |
| POST /releases/{id}/rollback | Queue rollback with an idempotency key, expected operation ID, reason and confirmation |
| GET /rollbacks, GET /rollbacks/{id}, GET /rollbacks/{id}/events | Inspect rollback operations |
| POST /deployments/{id}/reconcile | Queue an owner-requested read-only observation with an optional candidate release ID |

Workspace membership scopes reads. Owners and eligible developers can request non-production rollbacks. Production requires the owner role, the existing production flag, and explicit single-actor approval with a reason. Browser mutations use the existing CSRF check, and internal execution uses the existing worker authentication and fencing. The workspace audit log and deployment timeline record operation-specific outcomes. Rollback and reconciliation require the Kubernetes execution feature and a registered Kubernetes worker. Use trusted workers in an isolated environment.

## Verification

1. Run the backend and worker Maven tests and packages, then run frontend typecheck, tests, and build.
2. Run the PostgreSQL integration suite with TEST_POSTGRES_URL, TEST_POSTGRES_USER, TEST_POSTGRES_PASSWORD, and TEST_POSTGRES_RUN_FLYWAY=true against a disposable PostgreSQL 17 database.
3. Provision a disposable cluster and namespace following [the Kubernetes local runbook](../infrastructure/kubernetes/README.md). Set CLOUDFORGE_K8S_E2E_IMAGE_REF and CLOUDFORGE_K8S_E2E_SECOND_IMAGE_REF to different pinned, non-root images with a common probe and port. Run **mvn -Dtest=RealLocalKubernetesEndToEndTest test** in the worker module.
4. For a system validation, start the CloudForge control plane, PostgreSQL, and a trusted Kubernetes worker. Queue images A and B for the same Development workload. Compare revisions 1 and 2, preview revision 1, and submit rollback. Verify that revision 3 reports readiness and image A's digest. Reopen revisions 1 and 2 and examine the audit trail.
5. Exercise stale preview, duplicate requests, an expired lease, and post-apply cancellation on the test workload. Verify that a live observation is required before further mutations.
