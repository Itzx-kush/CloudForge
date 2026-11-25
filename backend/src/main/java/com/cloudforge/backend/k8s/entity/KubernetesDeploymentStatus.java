package com.cloudforge.backend.k8s.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Explicit deployment operation lifecycle states distinct from both Kubernetes Deployment
 * conditions and CloudForge's pipeline-run state machine.
 */
public enum KubernetesDeploymentStatus {
    PENDING(false),
    VALIDATING(false),
    APPLYING(false),
    ROLLING_OUT(false),
    VERIFYING(false),
    SUCCEEDED(true),
    FAILED(true),
    CANCELLED(true),
    RECONCILIATION_REQUIRED(true);

    private final boolean terminal;

    KubernetesDeploymentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean isActive() {
        return !terminal;
    }

    public boolean canTransitionTo(KubernetesDeploymentStatus target) {
        if (target == null || this.terminal) {
            return false;
        }
        if (this == target) {
            return this == ROLLING_OUT || this == VERIFYING || this == VALIDATING || this == APPLYING;
        }
        Set<KubernetesDeploymentStatus> allowed = switch (this) {
            case PENDING -> EnumSet.of(VALIDATING, FAILED, CANCELLED, RECONCILIATION_REQUIRED);
            case VALIDATING -> EnumSet.of(APPLYING, VERIFYING, FAILED, CANCELLED, RECONCILIATION_REQUIRED);
            case APPLYING -> EnumSet.of(ROLLING_OUT, VERIFYING, FAILED, CANCELLED, RECONCILIATION_REQUIRED);
            case ROLLING_OUT -> EnumSet.of(VERIFYING, FAILED, CANCELLED, RECONCILIATION_REQUIRED);
            case VERIFYING -> EnumSet.of(SUCCEEDED, FAILED, CANCELLED, RECONCILIATION_REQUIRED);
            default -> EnumSet.noneOf(KubernetesDeploymentStatus.class);
        };
        return allowed.contains(target);
    }
}
