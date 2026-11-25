#!/usr/bin/env bash
# Operator-run script: provisions an isolated, restricted CloudForge namespace,
# ResourceQuota, LimitRange, default-deny NetworkPolicy, least-privilege deployer
# ServiceAccount + Role + RoleBinding, and workload ServiceAccount.
#
# Never give normal deployment workers cluster-admin or namespace-creation rights.
# Usage:
#   KUBECONFIG=/path/to/isolated.kubeconfig ./provision-local-namespace.sh <namespace> [token_duration]
set -euo pipefail

NAMESPACE="${1:?Usage: $0 <namespace> [token_duration]}"
TOKEN_DURATION="${2:-24h}"

if [[ ! "$NAMESPACE" =~ ^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$ ]]; then
  echo "ERROR: Invalid DNS-1123 namespace name: $NAMESPACE" >&2
  exit 1
fi
case "$NAMESPACE" in
  default|kube-*|openshift-*|k8s-*)
    echo "ERROR: Refusing to provision reserved/control-plane namespace: $NAMESPACE" >&2
    exit 1
    ;;
esac

echo "Provisioning isolated namespace '$NAMESPACE' with Pod Security Standards (restricted)..."
kubectl apply -f - <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: ${NAMESPACE}
  labels:
    app.kubernetes.io/managed-by: cloudforge
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/enforce-version: latest
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/audit-version: latest
    pod-security.kubernetes.io/warn: restricted
    pod-security.kubernetes.io/warn-version: latest
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: cloudforge-namespace-quota
  namespace: ${NAMESPACE}
spec:
  hard:
    pods: "20"
    requests.cpu: "4"
    requests.memory: "4Gi"
    limits.cpu: "8"
    limits.memory: "8Gi"
    services: "10"
    services.loadbalancers: "0"
    services.nodeports: "0"
---
apiVersion: v1
kind: LimitRange
metadata:
  name: cloudforge-container-limits
  namespace: ${NAMESPACE}
spec:
  limits:
    - type: Container
      defaultRequest: { cpu: "100m", memory: "128Mi" }
      default: { cpu: "500m", memory: "512Mi" }
      min: { cpu: "10m", memory: "32Mi" }
      max: { cpu: "2", memory: "2Gi" }
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cloudforge-deployer
  namespace: ${NAMESPACE}
automountServiceAccountToken: false
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cloudforge-workload
  namespace: ${NAMESPACE}
automountServiceAccountToken: false
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: cloudforge-deployer-role
  namespace: ${NAMESPACE}
rules:
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "create", "update", "patch"]
  - apiGroups: ["apps"]
    resources: ["replicasets"]
    verbs: ["get", "list"]
  - apiGroups: [""]
    resources: ["services"]
    verbs: ["get", "list", "create", "update", "patch"]
  - apiGroups: [""]
    resources: ["pods", "events"]
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: cloudforge-deployer-binding
  namespace: ${NAMESPACE}
subjects:
  - kind: ServiceAccount
    name: cloudforge-deployer
    namespace: ${NAMESPACE}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: cloudforge-deployer-role
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: cloudforge-default-deny-all
  namespace: ${NAMESPACE}
spec:
  podSelector: {}
  policyTypes: ["Ingress", "Egress"]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: cloudforge-allow-dns-and-same-namespace
  namespace: ${NAMESPACE}
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/managed-by: cloudforge
  policyTypes: ["Ingress", "Egress"]
  ingress:
    - from:
        - podSelector: {}
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
EOF

echo ""
echo "Namespace '$NAMESPACE' provisioned successfully."
echo "Generate a bounded ServiceAccount bearer token to store in CloudForge Phase 3.4 Encrypted Secrets:"
echo "  kubectl -n ${NAMESPACE} create token cloudforge-deployer --duration=${TOKEN_DURATION}"
