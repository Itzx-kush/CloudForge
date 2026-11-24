package com.cloudforge.backend.worker.entity;

public enum AttemptFailureCategory {
    NONE(false),
    INFRASTRUCTURE_FAILURE(true),
    WORKER_CRASH(true),
    LEASE_EXPIRED(true),
    EXECUTION_TIMEOUT(true),
    OPERATION_FAILURE(false),
    CANCELLED(false),
    UNSUPPORTED_OPERATION(false),
    DOCKERFILE_POLICY_VIOLATION(false),
    SOURCE_CONTEXT_INVALID(false),
    BUILD_FAILED(false),
    REGISTRY_AUTH_FAILED(false),
    REGISTRY_UNAVAILABLE(true),
    DIGEST_VERIFICATION_FAILED(false),
    CLUSTER_UNAVAILABLE(false),
    CLUSTER_AUTH_FAILED(false),
    CLUSTER_TLS_ERROR(false),
    K8S_RBAC_DENIED(false),
    K8S_NAMESPACE_INVALID(false),
    K8S_MANIFEST_REJECTED(false),
    K8S_QUOTA_EXCEEDED(false),
    K8S_IMAGE_PULL_FAILED(false),
    K8S_CRASH_LOOP(false),
    K8S_UNSCHEDULABLE(false),
    K8S_READINESS_FAILED(false),
    K8S_ROLLOUT_TIMEOUT(false),
    K8S_RECONCILIATION_REQUIRED(false);

    private final boolean retryEligible;

    AttemptFailureCategory(boolean retryEligible) {
        this.retryEligible = retryEligible;
    }

    public boolean isRetryEligible() {
        return retryEligible;
    }
}
