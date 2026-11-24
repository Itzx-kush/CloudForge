package com.cloudforge.backend.k8s.entity;

public enum KubernetesClusterStatus {
    PENDING_VALIDATION,
    VALIDATING,
    VALIDATED,
    VALIDATION_FAILED,
    DISABLED
}
