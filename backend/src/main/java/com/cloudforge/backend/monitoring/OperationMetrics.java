package com.cloudforge.backend.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OperationMetrics {
    public record CommittedAction(String action) {}
    private static final Set<String> ACTIONS = Set.of(
            "PIPELINE_RUN_REQUESTED", "PIPELINE_RUN_STARTED", "PIPELINE_RUN_CANCELLED",
            "PIPELINE_RUN_SUCCEEDED", "PIPELINE_RUN_FAILED", "OCI_BUILD_STARTED",
            "OCI_BUILD_SUCCEEDED", "OCI_BUILD_FAILED", "OCI_PUBLISH_STARTED", "OCI_PUBLISH_FAILED",
            "OCI_ARTIFACT_PUBLISHED_VERIFIED", "OCI_DIGEST_VERIFICATION_FAILED",
            "KUBERNETES_DEPLOYMENT_REQUESTED", "KUBERNETES_DEPLOYMENT_SUCCEEDED", "KUBERNETES_DEPLOYMENT_FAILED",
            "KUBERNETES_ROLLBACK_REQUESTED", "KUBERNETES_ROLLBACK_SUCCEEDED", "KUBERNETES_ROLLBACK_FAILED",
            "KUBERNETES_RECONCILIATION_REQUESTED", "SECURITY_EVIDENCE_RECORDED", "SECRET_ACCESS_DENIED", "KUBERNETES_CLUSTER_VALIDATION_FAILED");
    private final MeterRegistry registry;

    public OperationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener
    public void record(CommittedAction event) {
        if (ACTIONS.contains(event.action())) {
            registry.counter("cloudforge.operations", "action", event.action()).increment();
        }
    }
}
