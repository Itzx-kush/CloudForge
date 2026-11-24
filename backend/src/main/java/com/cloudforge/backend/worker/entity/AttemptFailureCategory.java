package com.cloudforge.backend.worker.entity;

public enum AttemptFailureCategory {
    NONE(false),
    INFRASTRUCTURE_FAILURE(true),
    WORKER_CRASH(true),
    LEASE_EXPIRED(true),
    EXECUTION_TIMEOUT(true),
    OPERATION_FAILURE(false),
    CANCELLED(false),
    UNSUPPORTED_OPERATION(false);

    private final boolean retryEligible;

    AttemptFailureCategory(boolean retryEligible) {
        this.retryEligible = retryEligible;
    }

    public boolean isRetryEligible() {
        return retryEligible;
    }
}
