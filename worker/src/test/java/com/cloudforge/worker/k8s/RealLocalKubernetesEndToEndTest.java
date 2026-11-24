package com.cloudforge.worker.k8s;

import com.cloudforge.worker.client.ControlPlaneClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end-to-end Kubernetes worker verification executed against a disposable local
 * kind/k3d/k3s cluster when {@code CLOUDFORGE_K8S_E2E_API_URL} is explicitly provided.
 *
 * <p>Skipped cleanly in standard CI environments without a local Kubernetes cluster;
 * never connects to ambient {@code ~/.kube/config} or external production clusters.</p>
 */
class RealLocalKubernetesEndToEndTest {

    @Test
    void validatesClusterAndDeploysVerifiedImmutableImageAndDetectsInvalidDigestOnRealCluster() throws Exception {
        String apiUrl = System.getenv("CLOUDFORGE_K8S_E2E_API_URL");
        String caFile = System.getenv("CLOUDFORGE_K8S_E2E_CA_FILE");
        String token = System.getenv("CLOUDFORGE_K8S_E2E_TOKEN");
        String namespace = System.getenv("CLOUDFORGE_K8S_E2E_NAMESPACE");
        String imageRef = System.getenv("CLOUDFORGE_K8S_E2E_IMAGE_REF");
        String digest = System.getenv("CLOUDFORGE_K8S_E2E_IMAGE_DIGEST");
        int containerPort = Integer.parseInt(System.getenv().getOrDefault("CLOUDFORGE_K8S_E2E_PORT", "8080"));
        String probePath = System.getenv().getOrDefault("CLOUDFORGE_K8S_E2E_PROBE_PATH", "/");

        Assumptions.assumeTrue(
                apiUrl != null && !apiUrl.isBlank()
                        && caFile != null && !caFile.isBlank()
                        && token != null && !token.isBlank()
                        && namespace != null && !namespace.isBlank()
                        && imageRef != null && !imageRef.isBlank()
                        && digest != null && !digest.isBlank(),
                "Skipping real local Kubernetes cluster E2E (set CLOUDFORGE_K8S_E2E_* env vars to enable)"
        );

        String caPem = Files.readString(Path.of(caFile), StandardCharsets.UTF_8);
        OfficialKubernetesApiClient client = new OfficialKubernetesApiClient(true);
        KubernetesApiClient.ClusterConnectionContext ctx = new KubernetesApiClient.ClusterConnectionContext(
                apiUrl, caPem, token.toCharArray(), true, 20
        );

        // 1. Validate live cluster TLS, server version, apps/v1, least-privilege RBAC, and isolated namespace
        ControlPlaneClient.ClusterValidationTask valTask = new ControlPlaneClient.ClusterValidationTask(
                UUID.randomUUID(), UUID.randomUUID(), "local-e2e-cluster", "Local E2E Cluster", "K3S_LOCAL",
                apiUrl, "a".repeat(64), "cloudforge-deployer", true, 1L, List.of(namespace)
        );
        KubernetesClusterValidator.ValidationOutcome validation =
                new KubernetesClusterValidator(client).validate(valTask, ctx);
        assertThat(validation.valid())
                .withFailMessage("Cluster validation failed: %s", validation.errorSummary())
                .isTrue();
        assertThat(validation.verifiedNamespaces()).containsExactly(namespace);

        // 2. Deploy real hardened apps/v1 Deployment + v1 ClusterIP Service with immutable @sha256 digest
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        ControlPlaneClient.KubernetesExecutionDescriptor healthyDesc = new ControlPlaneClient.KubernetesExecutionDescriptor(
                UUID.randomUUID(), wsId, projId, envId, "DEVELOPMENT", UUID.randomUUID(),
                "local-e2e-cluster", apiUrl, true,
                UUID.randomUUID(), namespace, "cloudforge-workload", null,
                UUID.randomUUID(), UUID.randomUUID(),
                "cf-e2e-demo", "cf-e2e-demo",
                imageRef, digest, "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                null, 2, containerPort, 80,
                "50m", "250m", "64Mi", "128Mi",
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", probePath, containerPort, 2, 3, 2, 3, 1),
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", probePath, containerPort, 5, 5, 2, 3, 1),
                null, "1", "0", 120, 120, true, true, 10001L
        );

        List<String> transitions = new ArrayList<>();
        KubernetesDeploymentExecutor executor = new KubernetesDeploymentExecutor(client, 1000L);
        KubernetesDeploymentExecutor.DeploymentExecutionOutcome healthyOutcome = executor.execute(
                healthyDesc,
                ctx,
                (st, app, obs, upd, rdy, avail, gen, duid, suid, cond, pods, msg) -> transitions.add(st),
                new AtomicBoolean(false),
                (stream, line, ts) -> System.out.println("[K8S-E2E] " + line)
        );

        assertThat(healthyOutcome.deploymentStatus())
                .withFailMessage("Real Kubernetes deployment failed: %s (%s)",
                        healthyOutcome.summary(), healthyOutcome.failureCategory())
                .isEqualTo("SUCCEEDED");
        assertThat(healthyOutcome.readyReplicas()).isEqualTo(2);
        assertThat(healthyOutcome.availableReplicas()).isEqualTo(2);
        assertThat(healthyOutcome.verifiedImageDigest()).isEqualTo(digest);
        assertThat(healthyOutcome.k8sDeploymentUid()).isNotBlank();
        assertThat(healthyOutcome.k8sServiceUid()).isNotBlank();
        assertThat(transitions).contains("VALIDATING", "APPLYING", "ROLLING_OUT", "VERIFYING");

        // 3. Negative real-cluster test: nonexistent immutable digest triggers K8S_IMAGE_PULL_FAILED (never SUCCEEDED)
        String bogusDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000000";
        String bogusRef = imageRef.substring(0, imageRef.indexOf('@')) + "@" + bogusDigest;
        ControlPlaneClient.KubernetesExecutionDescriptor brokenDesc = new ControlPlaneClient.KubernetesExecutionDescriptor(
                UUID.randomUUID(), wsId, projId, envId, "DEVELOPMENT", UUID.randomUUID(),
                "local-e2e-cluster", apiUrl, true,
                UUID.randomUUID(), namespace, "cloudforge-workload", null,
                UUID.randomUUID(), UUID.randomUUID(),
                "cf-e2e-bad-digest", "cf-e2e-bad-digest",
                bogusRef, bogusDigest, "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                null, 1, containerPort, 80,
                "50m", "250m", "64Mi", "128Mi",
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", probePath, containerPort, 2, 3, 2, 3, 1),
                new ControlPlaneClient.KubernetesProbeSpec("HTTP_GET", probePath, containerPort, 5, 5, 2, 3, 1),
                null, "1", "0", 60, 60, true, true, 10001L
        );

        KubernetesDeploymentExecutor.DeploymentExecutionOutcome brokenOutcome = executor.execute(
                brokenDesc,
                ctx,
                (st, app, obs, upd, rdy, avail, gen, duid, suid, cond, pods, msg) -> {},
                new AtomicBoolean(false),
                (stream, line, ts) -> System.out.println("[K8S-E2E-NEG] " + line)
        );
        assertThat(brokenOutcome.deploymentStatus()).isEqualTo("FAILED");
        assertThat(brokenOutcome.failureCategory()).isEqualTo("K8S_IMAGE_PULL_FAILED");
    }
}
