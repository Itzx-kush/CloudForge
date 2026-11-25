package com.cloudforge.backend.k8s.service;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.k8s.dto.KubernetesProbeConfig;
import com.cloudforge.backend.oci.service.OciBuildLifecycleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic generator for CloudForge's supported v1 Kubernetes workload manifests:
 * strictly {@code apps/v1 Deployment} + {@code v1 ClusterIP Service} with hardened container
 * security contexts, CloudForge ownership labels, immutable {@code @sha256:...} image digests,
 * and zero arbitrary user YAML execution.
 */
@Service
public class KubernetesManifestGenerator {

    private final ObjectMapper canonicalMapper;

    public KubernetesManifestGenerator(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public GeneratedManifestBundle generate(ManifestInput input) {
        if (!OciBuildLifecycleService.isValidDigest(input.manifestDigest())
                || input.immutableImageReference() == null
                || !input.immutableImageReference().endsWith("@" + input.manifestDigest())) {
            throw new BadRequestException(
                    "Kubernetes manifest generation strictly requires a verified immutable image reference (@sha256:...)"
            );
        }
        if (ClusterSecurityValidator.isForbiddenNamespace(input.namespaceName())) {
            throw new BadRequestException("Forbidden Kubernetes namespace: " + input.namespaceName());
        }

        Map<String, String> selectorLabels = new LinkedHashMap<>();
        selectorLabels.put("app.kubernetes.io/managed-by", "cloudforge");
        selectorLabels.put("app.kubernetes.io/name", input.deploymentName());
        selectorLabels.put("cloudforge.io/environment-id", input.environmentId().toString());
        selectorLabels.put("cloudforge.io/project-id", input.projectId().toString());
        selectorLabels.put("cloudforge.io/workspace-id", input.workspaceId().toString());

        Map<String, String> resourceLabels = new LinkedHashMap<>(selectorLabels);
        resourceLabels.put("cloudforge.io/environment-type", input.environmentType().toLowerCase(Locale.ROOT));
        if (input.deploymentConfigId() != null) {
            resourceLabels.put("cloudforge.io/deployment-config-id", input.deploymentConfigId().toString());
        }

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("cloudforge.io/artifact-id", input.ociArtifactId().toString());
        annotations.put("cloudforge.io/image-digest", input.manifestDigest());
        annotations.put("cloudforge.io/source-commit", input.sourceCommitSha());

        Map<String, Object> containerSecCtx = new LinkedHashMap<>();
        containerSecCtx.put("allowPrivilegeEscalation", false);
        containerSecCtx.put("capabilities", Map.of("drop", List.of("ALL")));
        containerSecCtx.put("privileged", false);
        containerSecCtx.put("readOnlyRootFilesystem", input.readOnlyRootFilesystem());
        containerSecCtx.put("runAsNonRoot", input.runAsNonRoot());
        if (input.runAsNonRoot()) {
            containerSecCtx.put("runAsUser", input.runAsUser());
        }

        Map<String, Object> container = new LinkedHashMap<>();
        container.put("image", input.immutableImageReference());
        container.put("imagePullPolicy", "IfNotPresent");
        container.put("livenessProbe", toProbeMap(input.livenessProbe()));
        container.put("name", "app");
        container.put("ports", List.of(Map.of(
                "containerPort", input.containerPort(),
                "name", "http",
                "protocol", "TCP"
        )));
        container.put("readinessProbe", toProbeMap(input.readinessProbe()));
        container.put("resources", Map.of(
                "limits", Map.of("cpu", input.cpuLimit(), "memory", input.memoryLimit()),
                "requests", Map.of("cpu", input.cpuRequest(), "memory", input.memoryRequest())
        ));
        container.put("securityContext", containerSecCtx);
        if (input.startupProbe() != null) {
            container.put("startupProbe", toProbeMap(input.startupProbe()));
        }
        if (input.readOnlyRootFilesystem()) {
            container.put("volumeMounts", List.of(Map.of("mountPath", "/tmp", "name", "tmp")));
        }

        Map<String, Object> podSecCtx = new LinkedHashMap<>();
        podSecCtx.put("fsGroup", input.runAsUser());
        podSecCtx.put("runAsNonRoot", input.runAsNonRoot());
        if (input.runAsNonRoot()) {
            podSecCtx.put("runAsUser", input.runAsUser());
        }
        podSecCtx.put("seccompProfile", Map.of("type", "RuntimeDefault"));

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("automountServiceAccountToken", false);
        podSpec.put("containers", List.of(container));
        podSpec.put("hostIPC", false);
        podSpec.put("hostNetwork", false);
        podSpec.put("hostPID", false);
        if (StringUtils.hasText(input.imagePullSecretName())) {
            podSpec.put("imagePullSecrets", List.of(Map.of("name", input.imagePullSecretName().trim())));
        }
        podSpec.put("securityContext", podSecCtx);
        podSpec.put("serviceAccountName", input.workloadServiceAccountName());
        if (input.readOnlyRootFilesystem()) {
            podSpec.put("volumes", List.of(Map.of(
                    "emptyDir", Map.of("sizeLimit", "64Mi"),
                    "name", "tmp"
            )));
        }

        Map<String, Object> rollingUpdate = new LinkedHashMap<>();
        rollingUpdate.put("maxSurge", parseIntOrString(input.rollingMaxSurge()));
        rollingUpdate.put("maxUnavailable", parseIntOrString(input.rollingMaxUnavailable()));

        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("apiVersion", "apps/v1");
        deployment.put("kind", "Deployment");
        deployment.put("metadata", Map.of(
                "annotations", annotations,
                "labels", resourceLabels,
                "name", input.deploymentName(),
                "namespace", input.namespaceName()
        ));
        deployment.put("spec", Map.of(
                "progressDeadlineSeconds", input.progressDeadlineSeconds(),
                "replicas", input.replicaCount(),
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
                "name", input.serviceName(),
                "namespace", input.namespaceName()
        ));
        service.put("spec", Map.of(
                "ports", List.of(Map.of(
                        "name", "http",
                        "port", input.servicePort(),
                        "protocol", "TCP",
                        "targetPort", input.containerPort()
                )),
                "selector", selectorLabels,
                "type", "ClusterIP"
        ));

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("deployment", deployment);
        bundle.put("service", service);

        try {
            String deploymentJson = canonicalMapper.writeValueAsString(deployment);
            String serviceJson = canonicalMapper.writeValueAsString(service);
            String canonicalBundleJson = canonicalMapper.writeValueAsString(bundle);
            String checksum = sha256Hex(canonicalBundleJson);
            return new GeneratedManifestBundle(deploymentJson, serviceJson, checksum);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize canonical Kubernetes manifests", ex);
        }
    }

    private static Map<String, Object> toProbeMap(KubernetesProbeConfig probe) {
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

    public record ManifestInput(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            String environmentType,
            UUID deploymentConfigId,
            UUID ociArtifactId,
            String namespaceName,
            String workloadServiceAccountName,
            String imagePullSecretName,
            String deploymentName,
            String serviceName,
            String immutableImageReference,
            String manifestDigest,
            String sourceCommitSha,
            int replicaCount,
            int containerPort,
            int servicePort,
            String cpuRequest,
            String cpuLimit,
            String memoryRequest,
            String memoryLimit,
            KubernetesProbeConfig readinessProbe,
            KubernetesProbeConfig livenessProbe,
            KubernetesProbeConfig startupProbe,
            String rollingMaxSurge,
            String rollingMaxUnavailable,
            int progressDeadlineSeconds,
            boolean readOnlyRootFilesystem,
            boolean runAsNonRoot,
            long runAsUser
    ) {
    }

    public record GeneratedManifestBundle(
            String deploymentJson,
            String serviceJson,
            String manifestChecksumSha256
    ) {
    }
}
