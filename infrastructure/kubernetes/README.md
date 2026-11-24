# CloudForge Kubernetes Infrastructure Templates & Local Verification (`infrastructure/kubernetes/`)

This directory contains version-controlled, hardened Kubernetes manifests, least-privilege RBAC templates, network isolation policies, and local disposable cluster provisioning/verification scripts for **CloudForge Phase 3.6 — Secure Kubernetes Deployment Engine**.

> **Security Notice:** Zero kubeconfig files, bearer tokens, private keys, or production cluster endpoints are stored here. Never commit kubeconfigs or cluster credentials to Git.

---

## Directory Layout

| Path | Purpose |
| --- | --- |
| `base/namespace-template.yaml` | Namespace enforcing Pod Security Admission (`pod-security.kubernetes.io/enforce: restricted`) and CloudForge ownership labels. |
| `base/resource-quota.yaml` | Namespace `ResourceQuota` bounding pods, CPU/memory requests & limits, services, and configmaps. |
| `base/limit-range.yaml` | Namespace `LimitRange` enforcing conservative default and maximum per-container resource bounds. |
| `base/deployment-template.yaml` | Hardened `apps/v1 Deployment` reference template (non-root UID `10001`, `allowPrivilegeEscalation: false`, `drop: [ALL]`, `RuntimeDefault` seccomp, read-only root filesystem with bounded `/tmp` `emptyDir`, `automountServiceAccountToken: false`, immutable `@sha256:` image reference, readiness & liveness probes, `RollingUpdate` strategy). |
| `base/service-template.yaml` | Internal-only `v1 ClusterIP Service` reference template matching CloudForge ownership selectors. |
| `rbac/deployer-service-account.yaml` | Least-privilege `cloudforge-deployer` `ServiceAccount` (`automountServiceAccountToken: false`) used by dedicated `KUBERNETES_DEPLOY` workers. |
| `rbac/deployer-role.yaml` | Namespace-scoped `Role` granting only `apps/deployments`, `services`, `pods`, `replicasets`, and `events` inspection/apply operations (explicitly **zero** `secrets`, `roles`, `rolebindings`, `serviceaccounts`, `namespaces`, or cluster-admin permissions). |
| `rbac/deployer-role-binding.yaml` | Namespace-scoped `RoleBinding` binding `cloudforge-deployer` to `cloudforge-deployer-role` (no `ClusterRoleBinding`). |
| `rbac/workload-service-account.yaml` | Dedicated zero-privilege `cloudforge-workload` `ServiceAccount` (`automountServiceAccountToken: false`) assigned to deployed application pods. |
| `network-policies/default-deny-all.yaml` | Default-deny ingress and egress `NetworkPolicy` isolating all pods in the target namespace. |
| `network-policies/allow-dns-and-app-ingress.yaml` | Narrowly scoped `NetworkPolicy` permitting UDP/TCP port 53 DNS egress to `kube-system` and ingress to the application port from approved namespace-local peers. |
| `local/kind-cluster-config.yaml` | Reference local `kind` cluster configuration (with `disableDefaultCNI: true` note for installing a NetworkPolicy-capable CNI such as Calico/Cilium). |
| `local/k3d-cluster-config.yaml` | Reference local `k3d` cluster configuration (ships with K3s + `kube-router` NetworkPolicy enforcement enabled out of the box). |
| `local/provision-local-namespace.sh` / `.ps1` | Operator scripts to provision an isolated namespace with Restricted PSA, `ResourceQuota`, `LimitRange`, `NetworkPolicy`, least-privilege RBAC, and mint a short-lived `kubectl create token` bearer token for Phase 3.4 encrypted secret storage. |
| `tests/rbac-verification.sh` / `.ps1` | Live `kubectl auth can-i` verification scripts proving both permitted namespace deployment operations AND denied forbidden operations (`secrets`, `clusterrolebindings`, `kube-system`, `namespaces`, `rolebindings`). |
| `tests/network-policy-test-pods.yaml` | Hardened non-root test pods to verify real CNI `NetworkPolicy` traffic enforcement (blocked unauthorized cross-namespace probe vs. permitted intra-namespace traffic). |

---

## Operator Provisioning Workflow (Local Disposable Cluster)

1. Create a uniquely named disposable local cluster (`k3d`, `kind` with Calico/Cilium, or disposable `rancher/k3s`) using an **isolated temporary kubeconfig** (never overwriting your primary `~/.kube/config` or targeting production clusters).
2. Provision an operator-approved namespace and least-privilege `cloudforge-deployer` identity:
   ```powershell
   $env:KUBECONFIG = "$env:TEMP\cloudforge-e2e.kubeconfig"
   ./infrastructure/kubernetes/local/provision-local-namespace.ps1 `
     -Namespace "cf-local-payments-dev" `
     -TokenDuration "2h"
   ```
   *(On Linux/macOS, use `KUBECONFIG=/tmp/cloudforge-e2e.kubeconfig ./infrastructure/kubernetes/local/provision-local-namespace.sh cf-local-payments-dev`.)*
3. Verify RBAC boundaries immediately using Kubernetes authorization checks:
   ```powershell
   $env:KUBECONFIG = "$env:TEMP\cloudforge-e2e.kubeconfig"
   ./infrastructure/kubernetes/tests/rbac-verification.ps1 `
     -Namespace "cf-local-payments-dev"
   ```
4. Store the short-lived `cloudforge-deployer` bearer token in CloudForge's **Phase 3.4 Encrypted Secrets** vault, register the cluster in `/deployments` with its public X.509 CA PEM certificate, bind the namespace, and trigger worker-executed cluster validation.

---

## Important RBAC & NetworkPolicy Caveats

- **`create` privilege caveat:** Kubernetes RBAC cannot enforce arbitrary field-level restrictions on `create` verbs (e.g., container security contexts or image digest pinning) on `apps/deployments` alone. CloudForge therefore enforces layered controls: namespace Pod Security Admission (`enforce: restricted`), `ResourceQuota`/`LimitRange`, worker-side `KubernetesManifestBuilder` checksum & security policy verification, Server-Side Apply with `force=false`, and ownership-label verification before writes.
- **`NetworkPolicy` CNI caveat:** Creating `NetworkPolicy` objects does **not** isolate pod traffic unless the cluster runs a policy-enforcing CNI (such as K3s `kube-router`, Calico, or Cilium). Default `kind` (`kindnet`) does **not** enforce `NetworkPolicy`; always test live pod-to-pod traffic before reporting `ENFORCED_VERIFIED`.
- **Phase 3.7 Rollback Boundary:** Phase 3.6 records immutable release evidence (commit SHA, `@sha256:` image digest, manifest SHA-256 checksum, Kubernetes UIDs, actor, and rollout outcome) to support future Phase 3.7 rollback, but automatic or one-click rollback is **not** implemented in Phase 3.6.
