package com.cloudforge.worker.k8s;

import com.cloudforge.worker.client.ControlPlaneClient;
import com.cloudforge.worker.config.WorkerAgentConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for worker-side Kubernetes manifest construction, checksum enforcement,
 * container/pod security invariants, and WorkerAgentConfig mutual exclusion & HTTPS safety.
 */
class KubernetesManifestAndSecurityTest {

    private static final String VALID_DIGEST =
            "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    void buildsDeterministicHardenedManifestsAndVerifiesChecksumParity() {
        ControlPlaneClient.KubernetesExecutionDescriptor base = validDescriptor(null);
        KubernetesManifestBuilder.BuiltManifests built1 = KubernetesManifestBuilder.buildAndVerify(base);
        KubernetesManifestBuilder.BuiltManifests built2 = KubernetesManifestBuilder.buildAndVerify(base);

        assertThat(built1.manifestChecksumSha256()).isEqualTo(built2.manifestChecksumSha256());
        assertThat(built1.manifestChecksumSha256()).matches("^[0-9a-f]{64}$");
        assertThat(built1.deploymentJson())
                .contains("\"apiVersion\":\"apps/v1\"")
                .contains("\"kind\":\"Deployment\"")
                .contains("\"privileged\":false")
                .contains("\"allowPrivilegeEscalation\":false")
                .contains("\"drop\":[\"ALL\"]")
                .contains("\"hostNetwork\":false")
                .contains("\"hostPID\":false")
                .contains("\"hostIPC\":false")
                .contains("\"automountServiceAccountToken\":false")
                .contains("\"type\":\"RuntimeDefault\"")
                .contains("@" + VALID_DIGEST);
        assertThat(built1.serviceJson())
                .contains("\"apiVersion\":\"v1\"")
                .contains("\"kind\":\"Service\"")
                .contains("\"type\":\"ClusterIP\"");

        // Matching checksum passes (same descriptor UUIDs & inputs)
        ControlPlaneClient.KubernetesExecutionDescriptor withChecksum = withChecksum(base, built1.manifestChecksumSha256());
        assertThat(KubernetesManifestBuilder.buildAndVerify(withChecksum).manifestChecksumSha256())
                .isEqualTo(built1.manifestChecksumSha256());

        // Tampered checksum rejected before apply
        ControlPlaneClient.KubernetesExecutionDescriptor tampered = withChecksum(base, "0".repeat(64));
        assertThatThrownBy(() -> KubernetesManifestBuilder.buildAndVerify(tampered))
                .isInstanceOf(KubernetesManifestBuilder.ManifestPolicyException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsForbiddenNamespacesMutableTagsRootUidDefaultSaAndExecProbes() {
        // kube-system forbidden
        ControlPlaneClient.KubernetesExecutionDescriptor kubeSys = copyWith(
                "kube-system", "127.0.0.1:5000/cloudforge/app@" + VALID_DIGEST,
                VALID_DIGEST, 2, 10001L, "cloudforge-workload",
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", "/healthz", 8080, 5, 5, 3, 3, 1)
        );
        assertThatThrownBy(() -> KubernetesManifestBuilder.buildAndVerify(kubeSys))
                .isInstanceOf(KubernetesManifestBuilder.ManifestPolicyException.class)
                .hasMessageContaining("forbidden");

        // Mutable tag rejected
        ControlPlaneClient.KubernetesExecutionDescriptor mutableTag = copyWith(
                "cf-ws01-app-dev", "127.0.0.1:5000/cloudforge/app:latest",
                VALID_DIGEST, 2, 10001L, "cloudforge-workload",
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", "/healthz", 8080, 5, 5, 3, 3, 1)
        );
        assertThatThrownBy(() -> KubernetesManifestBuilder.buildAndVerify(mutableTag))
                .isInstanceOf(KubernetesManifestBuilder.ManifestPolicyException.class)
                .hasMessageContaining("immutable @sha256");

        // Root UID 0 rejected
        ControlPlaneClient.KubernetesExecutionDescriptor rootUid = copyWith(
                "cf-ws01-app-dev", "127.0.0.1:5000/cloudforge/app@" + VALID_DIGEST,
                VALID_DIGEST, 2, 0L, "cloudforge-workload",
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", "/healthz", 8080, 5, 5, 3, 3, 1)
        );
        assertThatThrownBy(() -> KubernetesManifestBuilder.buildAndVerify(rootUid))
                .isInstanceOf(KubernetesManifestBuilder.ManifestPolicyException.class)
                .hasMessageContaining("non-root");

        // default ServiceAccount rejected
        ControlPlaneClient.KubernetesExecutionDescriptor defaultSa = copyWith(
                "cf-ws01-app-dev", "127.0.0.1:5000/cloudforge/app@" + VALID_DIGEST,
                VALID_DIGEST, 2, 10001L, "default",
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", "/healthz", 8080, 5, 5, 3, 3, 1)
        );
        assertThatThrownBy(() -> KubernetesManifestBuilder.buildAndVerify(defaultSa))
                .isInstanceOf(KubernetesManifestBuilder.ManifestPolicyException.class)
                .hasMessageContaining("ServiceAccount");

        // EXEC probe rejected
        ControlPlaneClient.KubernetesExecutionDescriptor execProbe = copyWith(
                "cf-ws01-app-dev", "127.0.0.1:5000/cloudforge/app@" + VALID_DIGEST,
                VALID_DIGEST, 2, 10001L, "cloudforge-workload",
                new ControlPlaneClient.KubernetesProbeSpec("EXEC", null, 8080, 5, 5, 3, 3, 1)
        );
        assertThatThrownBy(() -> KubernetesManifestBuilder.buildAndVerify(execProbe))
                .isInstanceOf(KubernetesManifestBuilder.ManifestPolicyException.class)
                .hasMessageContaining("Unsupported probe type");
    }

    @Test
    void workerAgentConfigEnforcesKubernetesWorkerIsolationAndHttpsCredentialDelivery() {
        // Default: Kubernetes deploy mode is OFF
        WorkerAgentConfig defaultCfg = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_WORKER_ID", "plain-worker",
                "CLOUDFORGE_WORKER_TOKEN", "worker-token-1234567890"
        ));
        assertThat(defaultCfg.kubernetesDeployEnabled()).isFalse();
        assertThat(defaultCfg.supportedOperations()).doesNotContain("KUBERNETES_DEPLOY");

        // Dedicated KUBERNETES_DEPLOY mode advertises ONLY KUBERNETES_DEPLOY
        WorkerAgentConfig k8sCfg = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.example",
                "CLOUDFORGE_WORKER_ID", "k8s-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "worker-token-1234567890",
                "CLOUDFORGE_WORKER_KUBERNETES_DEPLOY_ENABLED", "true"
        ));
        assertThat(k8sCfg.kubernetesDeployEnabled()).isTrue();
        assertThat(k8sCfg.supportedOperations()).containsExactly("KUBERNETES_DEPLOY");

        // Cannot combine KUBERNETES_DEPLOY with OCI_BUILDKIT
        assertThatThrownBy(() -> WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.example",
                "CLOUDFORGE_WORKER_ID", "bad-combo-worker",
                "CLOUDFORGE_WORKER_TOKEN", "worker-token-1234567890",
                "CLOUDFORGE_WORKER_KUBERNETES_DEPLOY_ENABLED", "true",
                "CLOUDFORGE_WORKER_OCI_BUILDKIT_ENABLED", "true"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");

        // Cannot combine KUBERNETES_DEPLOY with GITHUB_SOURCE
        assertThatThrownBy(() -> WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.example",
                "CLOUDFORGE_WORKER_ID", "bad-combo-worker-2",
                "CLOUDFORGE_WORKER_TOKEN", "worker-token-1234567890",
                "CLOUDFORGE_WORKER_KUBERNETES_DEPLOY_ENABLED", "true",
                "CLOUDFORGE_WORKER_SOURCE_ONLY", "true"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");

        // Non-HTTPS control plane rejected unless insecure local opt-in is explicit
        assertThatThrownBy(() -> WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "http://control.cloudforge.example:8080",
                "CLOUDFORGE_WORKER_ID", "k8s-http-worker",
                "CLOUDFORGE_WORKER_TOKEN", "worker-token-1234567890",
                "CLOUDFORGE_WORKER_KUBERNETES_DEPLOY_ENABLED", "true"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");
    }

    static ControlPlaneClient.KubernetesExecutionDescriptor validDescriptor(String expectedChecksum) {
        return new ControlPlaneClient.KubernetesExecutionDescriptor(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "DEVELOPMENT", UUID.randomUUID(),
                "kind-dev", "https://127.0.0.1:16443", true,
                UUID.randomUUID(), "cf-ws01-payments-dev", "cloudforge-workload", null,
                UUID.randomUUID(), UUID.randomUUID(),
                "payments-api", "payments-api",
                "127.0.0.1:5000/cloudforge/payments@" + VALID_DIGEST,
                VALID_DIGEST, "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                expectedChecksum, 2, 8080, 80,
                "100m", "500m", "128Mi", "512Mi",
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", "/healthz", 8080, 5, 5, 3, 3, 1),
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", "/healthz", 8080, 15, 10, 3, 3, 1),
                null, "1", "0", 300, 30, true, true, 10001L
        );
    }

    @Test
    void releaseDescriptorBindsModeToTheCanonicalChecksum() {
        var base = validDescriptor(null);
        var expected = KubernetesManifestBuilder.buildAndVerify(base).manifestChecksumSha256();
        var mode = new ControlPlaneClient.KubernetesReleaseExecution("RECONCILIATION", base.immutableImageReference(), null, null, null);
        assertThat(KubernetesManifestBuilder.buildAndVerify(forRelease(base, base.immutableImageReference(), base.manifestDigest(),
                "RECONCILIATION:" + expected, mode)).manifestChecksumSha256()).isEqualTo(expected);
        assertThatThrownBy(() -> KubernetesManifestBuilder.buildAndVerify(
                forRelease(base, base.immutableImageReference(), base.manifestDigest(), expected, mode)))
                .isInstanceOf(KubernetesManifestBuilder.ManifestPolicyException.class);
    }

    static ControlPlaneClient.KubernetesExecutionDescriptor forRelease(
            ControlPlaneClient.KubernetesExecutionDescriptor d, String image, String digest, String checksum,
            ControlPlaneClient.KubernetesReleaseExecution release
    ) {
        return new ControlPlaneClient.KubernetesExecutionDescriptor(
                UUID.randomUUID(), d.workspaceId(), d.projectId(), d.environmentId(), d.environmentType(),
                d.clusterConnectionId(), d.clusterIdentifier(), d.apiServerUrl(), d.allowLocalLoopback(),
                d.namespaceBindingId(), d.namespaceName(), d.workloadServiceAccountName(), d.imagePullSecretName(),
                d.deploymentConfigId(), d.ociArtifactId(), d.deploymentName(), d.serviceName(), image, digest,
                d.sourceCommitSha(), checksum, d.replicaCount(), d.containerPort(), d.servicePort(), d.cpuRequest(),
                d.cpuLimit(), d.memoryRequest(), d.memoryLimit(), d.readinessProbe(), d.livenessProbe(), d.startupProbe(),
                d.rollingMaxSurge(), d.rollingMaxUnavailable(), d.progressDeadlineSeconds(), d.deploymentTimeoutSeconds(),
                d.readOnlyRootFilesystem(), d.runAsNonRoot(), d.runAsUser(), release);
    }

    private static ControlPlaneClient.KubernetesExecutionDescriptor withChecksum(
            ControlPlaneClient.KubernetesExecutionDescriptor d, String checksum
    ) {
        return new ControlPlaneClient.KubernetesExecutionDescriptor(
                d.deploymentOperationId(), d.workspaceId(), d.projectId(),
                d.environmentId(), d.environmentType(), d.clusterConnectionId(),
                d.clusterIdentifier(), d.apiServerUrl(), d.allowLocalLoopback(),
                d.namespaceBindingId(), d.namespaceName(), d.workloadServiceAccountName(), d.imagePullSecretName(),
                d.deploymentConfigId(), d.ociArtifactId(),
                d.deploymentName(), d.serviceName(),
                d.immutableImageReference(), d.manifestDigest(), d.sourceCommitSha(),
                checksum, d.replicaCount(), d.containerPort(), d.servicePort(),
                d.cpuRequest(), d.cpuLimit(), d.memoryRequest(), d.memoryLimit(),
                d.readinessProbe(), d.livenessProbe(), d.startupProbe(),
                d.rollingMaxSurge(), d.rollingMaxUnavailable(),
                d.progressDeadlineSeconds(), d.deploymentTimeoutSeconds(),
                d.readOnlyRootFilesystem(), d.runAsNonRoot(), d.runAsUser()
        );
    }

    private static ControlPlaneClient.KubernetesExecutionDescriptor copyWith(
            String namespace, String imageRef, String digest, int replicas, long runAsUser,
            String saName, ControlPlaneClient.KubernetesProbeSpec readinessProbe
    ) {
        return new ControlPlaneClient.KubernetesExecutionDescriptor(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "DEVELOPMENT", UUID.randomUUID(),
                "kind-dev", "https://127.0.0.1:16443", true,
                UUID.randomUUID(), namespace, saName, null,
                UUID.randomUUID(), UUID.randomUUID(),
                "payments-api", "payments-api",
                imageRef, digest, "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                null, replicas, 8080, 80,
                "100m", "500m", "128Mi", "512Mi",
                readinessProbe,
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", "/healthz", 8080, 15, 10, 3, 3, 1),
                null, "1", "0", 300, 30, true, true, runAsUser
        );
    }
}
