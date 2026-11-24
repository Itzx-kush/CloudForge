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
    DIGEST_VERIFICATION_FAILED(false);

    private final boolean retryEligible;

    AttemptFailureCategory(boolean retryEligible) {
        this.retryEligible = retryEligible;
    }

    public boolean isRetryEligible() {
        return retryEligible;
    }
}
