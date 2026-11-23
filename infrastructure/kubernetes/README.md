# Kubernetes Manifests & Helm Charts (Planned — Phase 3+)

## Phase 1 Status

No Kubernetes resources are deployed or executed in Phase 1.

## Planned Architecture

Future phases will add declarative cluster deployment templates here:

- Namespace isolation and RBAC policies (`Role`, `RoleBinding`, least-privilege `ServiceAccount`)
- Control-plane manifests (`Deployment`, `Service`, `Ingress`, `ConfigMap`, sealed/external secrets)
- Workload deployment templates with resource requests/limits, readiness/liveness probes, and `NetworkPolicy` isolation
- Rolling update and automated rollback strategies integrated with deployment history
- Local cluster verification profiles (kind / k3d / Minikube) before any cloud deployment target is introduced
