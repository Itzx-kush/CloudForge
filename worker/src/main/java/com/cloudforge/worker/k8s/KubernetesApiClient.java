package com.cloudforge.worker.k8s;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clean Kubernetes API boundary implemented by {@link OfficialKubernetesApiClient} using
 * the official Java 17 Kubernetes API client ({@code io.kubernetes:client-java}) without
 * shell command strings or ambient kubeconfig loading.
 */
public interface KubernetesApiClient {

    ServerVersionInfo getServerVersion(ClusterConnectionContext ctx);

    boolean hasAppsV1Api(ClusterConnectionContext ctx);

    boolean checkSelfSubjectAccess(
            ClusterConnectionContext ctx,
            String namespace,
            String group,
            String resource,
            String verb
    );

    NamespaceSnapshot inspectNamespace(ClusterConnectionContext ctx, String namespace);

    Optional<DeploymentSnapshot> getDeployment(ClusterConnectionContext ctx, String namespace, String name);

    DeploymentSnapshot applyDeployment(
            ClusterConnectionContext ctx,
            String namespace,
            String name,
            String deploymentJson
    );

    Optional<ServiceSnapshot> getService(ClusterConnectionContext ctx, String namespace, String name);

    ServiceSnapshot applyService(
            ClusterConnectionContext ctx,
            String namespace,
            String name,
            String serviceJson
    );

    List<PodSnapshot> listDeploymentPods(
            ClusterConnectionContext ctx,
            String namespace,
            Map<String, String> matchLabels
    );

    List<String> listRecentWarningEvents(
            ClusterConnectionContext ctx,
            String namespace,
            String involvedObjectName
    );

    record ClusterConnectionContext(
            String apiServerUrl,
            String caCertificatePem,
            char[] bearerToken,
            boolean allowLocalLoopback,
            int timeoutSeconds
    ) {
        @Override
        public String toString() {
            return "ClusterConnectionContext[apiServerUrl=" + apiServerUrl
                    + ", allowLocalLoopback=" + allowLocalLoopback + ", bearerToken=[REDACTED]]";
        }
    }

    record ServerVersionInfo(String gitVersion, String major, String minor, String platform) {
    }

    record NamespaceSnapshot(boolean exists, String name, String phase, Map<String, String> labels) {
    }

    record DeploymentConditionInfo(String type, String status, String reason, String message) {
    }

    record DeploymentSnapshot(
            String uid,
            String name,
            String namespace,
            long generation,
            long observedGeneration,
            int specReplicas,
            int statusReplicas,
            int updatedReplicas,
            int readyReplicas,
            int availableReplicas,
            Map<String, String> labels,
            List<String> containerImages,
            List<DeploymentConditionInfo> conditions
    ) {
    }

    record ServiceSnapshot(
            String uid,
            String name,
            String namespace,
            String type,
            Map<String, String> labels
    ) {
    }

    record ContainerStatusSnapshot(
            String name,
            String image,
            String imageId,
            boolean ready,
            int restartCount,
            String waitingReason,
            String waitingMessage,
            String terminatedReason
    ) {
    }

    record PodSnapshot(
            String name,
            String phase,
            boolean scheduled,
            String unschedulableReason,
            String unschedulableMessage,
            boolean ready,
            List<ContainerStatusSnapshot> containerStatuses
    ) {
    }

    final class KubernetesClientException extends RuntimeException {
        private final String failureCategory;
        private final int httpStatus;

        public KubernetesClientException(String failureCategory, int httpStatus, String message, Throwable cause) {
            super(message, cause);
            this.failureCategory = failureCategory;
            this.httpStatus = httpStatus;
        }

        public KubernetesClientException(String failureCategory, int httpStatus, String message) {
            super(message);
            this.failureCategory = failureCategory;
            this.httpStatus = httpStatus;
        }

        public String getFailureCategory() {
            return failureCategory;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }
}
