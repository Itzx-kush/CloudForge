#!/usr/bin/env bash
# Verifies BOTH permitted and forbidden RBAC operations for the least-privilege
# cloudforge-deployer ServiceAccount inside an isolated namespace.
set -euo pipefail

NAMESPACE="${1:?Usage: $0 <namespace>}"
SA="system:serviceaccount:${NAMESPACE}:cloudforge-deployer"

expect_can() {
  local expected="$1"
  shift
  local actual
  actual="$(kubectl auth can-i --as="$SA" "$@" 2>/dev/null || true)"
  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL: expected '$expected' got '$actual' for: $*" >&2
    exit 1
  fi
  echo "PASS ($expected): $*"
}

echo "Verifying permitted operations for $SA in $NAMESPACE..."
expect_can yes get deployments.apps -n "$NAMESPACE"
expect_can yes create deployments.apps -n "$NAMESPACE"
expect_can yes patch deployments.apps -n "$NAMESPACE"
expect_can yes get services -n "$NAMESPACE"
expect_can yes create services -n "$NAMESPACE"
expect_can yes patch services -n "$NAMESPACE"
expect_can yes list pods -n "$NAMESPACE"
expect_can yes list replicasets.apps -n "$NAMESPACE"
expect_can yes list events -n "$NAMESPACE"

echo "Verifying forbidden operations for $SA..."
expect_can no get secrets -n "$NAMESPACE"
expect_can no create secrets -n "$NAMESPACE"
expect_can no create rolebindings.rbac.authorization.k8s.io -n "$NAMESPACE"
expect_can no create serviceaccounts -n "$NAMESPACE"
expect_can no create namespaces
expect_can no create clusterrolebindings.rbac.authorization.k8s.io
expect_can no get deployments.apps -n kube-system
expect_can no create deployments.apps -n kube-system
expect_can no get pods -n default

echo "All RBAC least-privilege checks passed for $SA."
