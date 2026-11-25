package com.cloudforge.worker.k8s;

import com.cloudforge.worker.client.ControlPlaneClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic worker-side Kubernetes manifest builder and independent security validator.
 *
 * <p>Reconstructs the canonical {@code apps/v1 Deployment} and {@code v1 ClusterIP Service}
 * from the control plane's immutable {@link ControlPlaneClient.KubernetesExecutionDescriptor},
 * verifies exact SHA-256 parity against {@code manifestChecksumSha256}, and enforces all
 * workload security restrictions locally before any Kubernetes API write.</p>
 */
public final class KubernetesManifestBuilder {

    private static final Pattern OCI_DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> FORBIDDEN_NAMESPACES = Set.of(
            "default", "kube-system", "kube-public", "kube-node-lease",
            "ingress-nginx", "cert-manager", "istio-system", "calico-system", "tigera-operator"
    );

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private KubernetesManifestBuilder() {
    }

    public static BuiltManifests buildAndVerify(ControlPlaneClient.KubernetesExecutionDescriptor d) {
        if (d == null) {
            throw new ManifestPolicyException("Deployment execution descriptor is missing");
        }
        validateDescriptorSecurity(d);

        Map<String, String> selectorLabels = new LinkedHashMap<>();
        selectorLabels.put("app.kubernetes.io/managed-by", "cloudforge");
        selectorLabels.put("app.kubernetes.io/name", d.deploymentName());
        selectorLabels.put("cloudforge.io/environment-id", d.environmentId().toString());
        selectorLabels.put("cloudforge.io/project-id", d.projectId().toString());
        selectorLabels.put("cloudforge.io/workspace-id", d.workspaceId().toString());

        Map<String, String> resourceLabels = new LinkedHashMap<>(selectorLabels);
        resourceLabels.put("cloudforge.io/environment-type", d.environmentType().toLowerCase(Locale.ROOT));
        if (d.deploymentConfigId() != null) {
            resourceLabels.put("cloudforge.io/deployment-config-id", d.deploymentConfigId().toString());
        }

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("cloudforge.io/artifact-id", d.ociArtifactId().toString());
        annotations.put("cloudforge.io/image-digest", d.manifestDigest());
        annotations.put("cloudforge.io/source-commit", d.sourceCommitSha());

        Map<String, Object> containerSecCtx = new LinkedHashMap<>();
        containerSecCtx.put("allowPrivilegeEscalation", false);
        containerSecCtx.put("capabilities", Map.of("drop", List.of("ALL")));
        containerSecCtx.put("privileged", false);
        containerSecCtx.put("readOnlyRootFilesystem", d.readOnlyRootFilesystem());
        containerSecCtx.put("runAsNonRoot", d.runAsNonRoot());
        if (d.runAsNonRoot()) {
            containerSecCtx.put("runAsUser", d.runAsUser());
        }

        Map<String, Object> container = new LinkedHashMap<>();
        container.put("image", d.immutableImageReference());
        container.put("imagePullPolicy", "IfNotPresent");
        container.put("livenessProbe", toProbeMap(d.livenessProbe()));
        container.put("name", "app");
        container.put("ports", List.of(Map.of(
                "containerPort", d.containerPort(),
                "name", "http",
                "protocol", "TCP"
        )));
        container.put("readinessProbe", toProbeMap(d.readinessProbe()));
        container.put("resources", Map.of(
                "limits", Map.of("cpu", d.cpuLimit(), "memory", d.memoryLimit()),
                "requests", Map.of("cpu", d.cpuRequest(), "memory", d.memoryRequest())
        ));
        container.put("securityContext", containerSecCtx);
        if (d.startupProbe() != null) {
            container.put("startupProbe", toProbeMap(d.startupProbe()));
        }
        if (d.readOnlyRootFilesystem()) {
            container.put("volumeMounts", List.of(Map.of("mountPath", "/tmp", "name", "tmp")));
        }

        Map<String, Object> podSecCtx = new LinkedHashMap<>();
        podSecCtx.put("fsGroup", d.runAsUser());
        podSecCtx.put("runAsNonRoot", d.runAsNonRoot());
        if (d.runAsNonRoot()) {
            podSecCtx.put("runAsUser", d.runAsUser());
        }
        podSecCtx.put("seccompProfile", Map.of("type", "RuntimeDefault"));

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("automountServiceAccountToken", false);
        podSpec.put("containers", List.of(container));
        podSpec.put("hostIPC", false);
        podSpec.put("hostNetwork", false);
        podSpec.put("hostPID", false);
        if (d.imagePullSecretName() != null && !d.imagePullSecretName().isBlank()) {
            podSpec.put("imagePullSecrets", List.of(Map.of("name", d.imagePullSecretName().trim())));
        }
        podSpec.put("securityContext", podSecCtx);
        podSpec.put("serviceAccountName", d.workloadServiceAccountName());
        if (d.readOnlyRootFilesystem()) {
            podSpec.put("volumes", List.of(Map.of(
                    "emptyDir", Map.of("sizeLimit", "64Mi"),
                    "name", "tmp"
            )));
        }

        Map<String, Object> rollingUpdate = new LinkedHashMap<>();
        rollingUpdate.put("maxSurge", parseIntOrString(d.rollingMaxSurge()));
        rollingUpdate.put("maxUnavailable", parseIntOrString(d.rollingMaxUnavailable()));

        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("apiVersion", "apps/v1");
        deployment.put("kind", "Deployment");
        deployment.put("metadata", Map.of(
                "annotations", annotations,
                "labels", resourceLabels,
                "name", d.deploymentName(),
                "namespace", d.namespaceName()
        ));
        deployment.put("spec", Map.of(
                "progressDeadlineSeconds", d.progressDeadlineSeconds(),
                "replicas", d.replicaCount(),
                "revisionHistoryLimit", 5,
                "selector", Map.of("matchLabels", selectorLabels),
                "strategy", Map.of(
                        "rollingUpdate", rollingUpdate,
                        "type", "RollingUpdate"
                ),
                "template", Map.of(
                        "metadata", Map.of(
                                "annotations", annotations,
                                "labels", resourceLabels
                        ),
                        "spec", podSpec
                )
        ));

        Map<String, Object> service = new LinkedHashMap<>();
        service.put("apiVersion", "v1");
        service.put("kind", "Service");
        service.put("metadata", Map.of(
                "annotations", annotations,
                "labels", resourceLabels,
                "name", d.serviceName(),
                "namespace", d.namespaceName()
        ));
        service.put("spec", Map.of(
                "ports", List.of(Map.of(
                        "name", "http",
                        "port", d.servicePort(),
                        "protocol", "TCP",
                        "targetPort", d.containerPort()
                )),
                "selector", selectorLabels,
                "type", "ClusterIP"
        ));

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("deployment", deployment);
        bundle.put("service", service);

        try {
            String deploymentJson = CANONICAL_MAPPER.writeValueAsString(deployment);
            String serviceJson = CANONICAL_MAPPER.writeValueAsString(service);
            String canonicalBundleJson = CANONICAL_MAPPER.writeValueAsString(bundle);
            String computedChecksum = sha256Hex(canonicalBundleJson);

            String protocolChecksum = d.releaseExecution() == null
                    ? computedChecksum : d.releaseExecution().kind() + ":" + computedChecksum;
            if ((d.releaseExecution() != null || (d.manifestChecksumSha256() != null && !d.manifestChecksumSha256().isBlank()))
                    && !protocolChecksum.equalsIgnoreCase(d.manifestChecksumSha256())) {
                throw new ManifestPolicyException(
                        "Manifest checksum verification failed: expected " + d.manifestChecksumSha256()
                                + " but computed " + computedChecksum
                );
            }
            assertSerializedManifestSafe(deploymentJson, serviceJson);
            return new BuiltManifests(deploymentJson, serviceJson, computedChecksum, Map.copyOf(selectorLabels));
        } catch (JsonProcessingException ex) {
            throw new ManifestPolicyException("Failed to serialize canonical Kubernetes manifests: " + ex.getMessage());
        }
    }

    public static void validateDescriptorSecurity(ControlPlaneClient.KubernetesExecutionDescriptor d) {
        if (d.releaseExecution() != null) {
            var release = d.releaseExecution();
            if ("ROLLBACK".equals(release.kind())) {
                if (release.expectedImage() == null || release.expectedDeploymentUid() == null
                        || release.expectedServiceUid() == null || release.expectedGeneration() == null
                        || release.expectedGeneration() < 1) {
                    throw new ManifestPolicyException("Rollback requires complete current workload preconditions");
                }
            } else if (!"RECONCILIATION".equals(release.kind())
                    || !d.immutableImageReference().equals(release.expectedImage())) {
                throw new ManifestPolicyException("Unsupported release execution descriptor");
            }
        }
        String ns = d.namespaceName() == null ? "" : d.namespaceName().trim().toLowerCase(Locale.ROOT);
        if (ns.isEmpty() || FORBIDDEN_NAMESPACES.contains(ns)
                || ns.startsWith("kube-") || ns.startsWith("openshift-") || ns.startsWith("k8s-")) {
            throw new ManifestPolicyException("Deployment into forbidden or control-plane namespace \"" + ns + "\" is prohibited");
        }
        if (d.manifestDigest() == null || !OCI_DIGEST_PATTERN.matcher(d.manifestDigest().trim().toLowerCase(Locale.ROOT)).matches()) {
            throw new ManifestPolicyException("Deployment requires a valid immutable sha256 OCI image digest");
        }
        if (d.immutableImageReference() == null
                || !d.immutableImageReference().endsWith("@" + d.manifestDigest().trim().toLowerCase(Locale.ROOT))) {
            throw new ManifestPolicyException("Image reference must use an immutable @sha256 digest — mutable tags are rejected");
        }
        if (d.replicaCount() < 1 || d.replicaCount() > 20) {
            throw new ManifestPolicyException("Replica count out of safe bounds (1..20): " + d.replicaCount());
        }
        if (d.runAsUser() < 1000L || d.runAsUser() > 65534L) {
            throw new ManifestPolicyException("runAsUser must be non-root UID 1000..65534");
        }
        if (d.workloadServiceAccountName() == null || d.workloadServiceAccountName().isBlank()
                || "default".equalsIgnoreCase(d.workloadServiceAccountName().trim())) {
            throw new ManifestPolicyException("Workload must use an approved least-privilege ServiceAccount, never default");
        }
        validateProbe(d.readinessProbe(), "readinessProbe");
        validateProbe(d.livenessProbe(), "livenessProbe");
        if (d.startupProbe() != null) {
            validateProbe(d.startupProbe(), "startupProbe");
        }
    }

    private static void validateProbe(ControlPlaneClient.KubernetesProbeSpec probe, String name) {
        if (probe == null || probe.type() == null) {
            throw new ManifestPolicyException("Missing required " + name);
        }
        String t = probe.type().trim().toUpperCase(Locale.ROOT);
        if (!"HTTP_GET".equals(t) && !"TCP_SOCKET".equals(t)) {
            throw new ManifestPolicyException("Unsupported probe type \"" + probe.type() + "\" on " + name);
        }
        if ("HTTP_GET".equals(t) && (probe.path() == null || !probe.path().startsWith("/"))) {
            throw new ManifestPolicyException("Invalid HTTP_GET path on " + name);
        }
    }

    private static void assertSerializedManifestSafe(String deploymentJson, String serviceJson) {
        if (deploymentJson.contains("\"privileged\":true")
                || deploymentJson.contains("\"allowPrivilegeEscalation\":true")
                || deploymentJson.contains("\"hostNetwork\":true")
                || deploymentJson.contains("\"hostPID\":true")
                || deploymentJson.contains("\"hostIPC\":true")
                || deploymentJson.contains("\"hostPath\"")
                || deploymentJson.contains("\"hostPort\"")
                || deploymentJson.contains("/var/run/docker.sock")
                || deploymentJson.contains("\"automountServiceAccountToken\":true")) {
            throw new ManifestPolicyException("Generated Deployment manifest violated container security invariants");
        }
        if (!serviceJson.contains("\"type\":\"ClusterIP\"")) {
            throw new ManifestPolicyException("Generated Service manifest must strictly use type ClusterIP");
        }
    }

    private static Map<String, Object> toProbeMap(ControlPlaneClient.KubernetesProbeSpec probe) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("failureThreshold", probe.failureThreshold());
        if ("HTTP_GET".equalsIgnoreCase(probe.type())) {
            map.put("httpGet", Map.of(
                    "path", probe.path(),
                    "port", probe.port(),
                    "scheme", "HTTP"
            ));
        } else {
            map.put("tcpSocket", Map.of("port", probe.port()));
        }
        map.put("initialDelaySeconds", probe.initialDelaySeconds());
        map.put("periodSeconds", probe.periodSeconds());
        map.put("successThreshold", probe.successThreshold());
        map.put("timeoutSeconds", probe.timeoutSeconds());
        return map;
    }

    private static Object parseIntOrString(String raw) {
        if (raw != null && raw.endsWith("%")) {
            return raw;
        }
        return Integer.parseInt(raw);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record BuiltManifests(
            String deploymentJson,
            String serviceJson,
            String manifestChecksumSha256,
            Map<String, String> selectorLabels
    ) {
    }

    public static final class ManifestPolicyException extends RuntimeException {
        public ManifestPolicyException(String message) {
            super(message);
        }
    }
}
