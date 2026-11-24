<#
.SYNOPSIS
Operator-run PowerShell script to provision an isolated CloudForge Kubernetes namespace
with Pod Security Admission (restricted), ResourceQuota, LimitRange, default-deny
NetworkPolicy, and least-privilege deployer & workload ServiceAccounts.

.EXAMPLE
$env:KUBECONFIG = "C:\path\to\isolated.kubeconfig"
.\provision-local-namespace.ps1 -Namespace "cf-12345678-demo-dev"
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Namespace,
    [string]$TokenDuration = "24h"
)

$ErrorActionPreference = "Stop"

if ($Namespace -notmatch '^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$') {
    throw "Invalid DNS-1123 namespace name: $Namespace"
}
if ($Namespace -eq "default" -or $Namespace -like "kube-*" -or $Namespace -like "openshift-*" -or $Namespace -like "k8s-*") {
    throw "Refusing to provision reserved/control-plane namespace: $Namespace"
}

$manifest = @"
apiVersion: v1
kind: Namespace
metadata:
  name: $Namespace
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
  namespace: $Namespace
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
  namespace: $Namespace
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
  namespace: $Namespace
automountServiceAccountToken: false
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cloudforge-workload
  namespace: $Namespace
automountServiceAccountToken: false
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: cloudforge-deployer-role
  namespace: $Namespace
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
  namespace: $Namespace
subjects:
  - kind: ServiceAccount
    name: cloudforge-deployer
    namespace: $Namespace
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: cloudforge-deployer-role
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: cloudforge-default-deny-all
  namespace: $Namespace
spec:
  podSelector: {}
  policyTypes: ["Ingress", "Egress"]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: cloudforge-allow-dns-and-same-namespace
  namespace: $Namespace
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
"@

$manifest | kubectl apply -f -
Write-Host "Namespace '$Namespace' provisioned successfully."
Write-Host "Issue a bounded token for CloudForge Phase 3.4 Encrypted Secrets:"
Write-Host "  kubectl -n $Namespace create token cloudforge-deployer --duration=$TokenDuration"
