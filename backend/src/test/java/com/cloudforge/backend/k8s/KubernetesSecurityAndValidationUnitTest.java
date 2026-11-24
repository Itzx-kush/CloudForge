package com.cloudforge.backend.k8s;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.k8s.config.KubernetesDeploymentProperties;
import com.cloudforge.backend.k8s.dto.CreateDeploymentRequest;
import com.cloudforge.backend.k8s.dto.KubernetesProbeConfig;
import com.cloudforge.backend.k8s.entity.KubernetesClusterType;
import com.cloudforge.backend.k8s.service.ClusterSecurityValidator;
import com.cloudforge.backend.k8s.service.KubernetesDeploymentConfigValidator;
import com.cloudforge.backend.k8s.service.KubernetesManifestGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests covering Phase 3.6 cluster SSRF/TLS/CA validation, namespace safety,
 * declarative workload config validation, and deterministic hardened manifest generation.
 */
class KubernetesSecurityAndValidationUnitTest {

    private static final String VALID_DIGEST =
            "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    private KubernetesDeploymentProperties props;
    private ClusterSecurityValidator clusterValidator;
    private KubernetesDeploymentConfigValidator configValidator;
    private KubernetesManifestGenerator manifestGenerator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        props = new KubernetesDeploymentProperties();
        props.setEnabled(true);
        props.setAllowLocalDevClusters(true);
        props.setAllowedApiHosts("127.0.0.1:16443,k8s.example.com:6443");
        clusterValidator = new ClusterSecurityValidator(props);
        configValidator = new KubernetesDeploymentConfigValidator(props, objectMapper);
        manifestGenerator = new KubernetesManifestGenerator(objectMapper);
    }

    @Test
    void clusterEndpointRejectsHttpMetadataPrivateLoopbackAndUnallowlistedHosts() {
        // Plaintext HTTP rejected
        assertThatThrownBy(() -> clusterValidator.validateClusterEndpoint(
                "http://127.0.0.1:16443", KubernetesClusterType.KIND_LOCAL, true))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("HTTPS");

        // Embedded userinfo / query rejected
        assertThatThrownBy(() -> clusterValidator.validateClusterEndpoint(
                "https://user:pass@127.0.0.1:16443", KubernetesClusterType.KIND_LOCAL, true))
                .isInstanceOf(BadRequestException.class);

        // Cloud metadata endpoints rejected even if someone tried to allowlist them
        props.setAllowedApiHosts("127.0.0.1:16443,169.254.169.254,metadata.google.internal,10.0.0.1:6443");
        assertThatThrownBy(() -> clusterValidator.validateClusterEndpoint(
                "https://169.254.169.254", KubernetesClusterType.REMOTE_KUBERNETES, false))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("SSRF");

        assertThatThrownBy(() -> clusterValidator.validateClusterEndpoint(
                "https://metadata.google.internal", KubernetesClusterType.REMOTE_KUBERNETES, false))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("SSRF");

        // Private RFC1918 address rejected by SSRF check
        assertThatThrownBy(() -> clusterValidator.validateClusterEndpoint(
                "https://10.0.0.1:6443", KubernetesClusterType.REMOTE_KUBERNETES, false))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("SSRF");

        // Loopback rejected for REMOTE_KUBERNETES or when local dev flag is disabled
        assertThatThrownBy(() -> clusterValidator.validateClusterEndpoint(
                "https://127.0.0.1:16443", KubernetesClusterType.REMOTE_KUBERNETES, true))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Loopback");

        props.setAllowLocalDevClusters(false);
        assertThatThrownBy(() -> clusterValidator.validateClusterEndpoint(
                "https://127.0.0.1:16443", KubernetesClusterType.KIND_LOCAL, true))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Loopback");

        // Explicitly allowed local dev loopback succeeds
        props.setAllowLocalDevClusters(true);
        var valid = clusterValidator.validateClusterEndpoint(
                "https://127.0.0.1:16443/", KubernetesClusterType.KIND_LOCAL, true);
        assertThat(valid.apiServerUrl()).isEqualTo("https://127.0.0.1:16443");
        assertThat(valid.allowLocalLoopback()).isTrue();

        // Unallowlisted host rejected
        assertThatThrownBy(() -> clusterValidator.validateClusterEndpoint(
                "https://127.0.0.1:9999", KubernetesClusterType.KIND_LOCAL, true))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void caCertificateRejectsPrivateKeysKubeconfigsAndMalformedPem() {
        assertThatThrownBy(() -> clusterValidator.validateCaCertificatePem(
                "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("never private keys");

        assertThatThrownBy(() -> clusterValidator.validateCaCertificatePem("apiVersion: v1\nkind: Config\nclient-key-data: abc"))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> clusterValidator.validateCaCertificatePem(
                "-----BEGIN CERTIFICATE-----\nNOT_VALID_DER\n-----END CERTIFICATE-----"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void namespaceValidationBlocksSystemNamespacesAndGeneratesDeterministicDnsSafeNames() {
        for (String forbidden : new String[]{"default", "kube-system", "kube-public", "kube-node-lease", "kube-anything", "openshift-monitoring", "istio-system"}) {
            assertThatThrownBy(() -> clusterValidator.validateNamespaceName(forbidden))
                    .isInstanceOf(ForbiddenOperationException.class);
        }

        assertThatThrownBy(() -> clusterValidator.validateServiceAccountName("default", "serviceAccountName"))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> clusterValidator.validateServiceAccountName("cluster-admin", "serviceAccountName"))
                .isInstanceOf(ForbiddenOperationException.class);

        UUID wsId = UUID.fromString("12345678-aaaa-bbbb-cccc-1234567890ab");
        String ns1 = clusterValidator.generateDeterministicNamespace(wsId, "Payments_API_Service_Very_Long_Name", EnvironmentType.STAGING);
        String ns2 = clusterValidator.generateDeterministicNamespace(wsId, "Payments_API_Service_Very_Long_Name", EnvironmentType.STAGING);
        assertThat(ns1).isEqualTo(ns2);
        assertThat(ns1).startsWith("cf-12345678-").endsWith("-stg");
        assertThat(ns1.length()).isLessThanOrEqualTo(63);
    }

    @Test
    void deploymentConfigValidatorEnforcesBoundsResourcesProbesRollingStrategyAndNonRootUid() {
        UUID any = UUID.randomUUID();

        // Negative / excessive replicas
        assertThatThrownBy(() -> configValidator.validate(baseReq(any, 0, "100m", "500m", "128Mi", "512Mi", "1", "0", null, null, 10001L), "app"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> configValidator.validate(baseReq(any, 25, "100m", "500m", "128Mi", "512Mi", "1", "0", null, null, 10001L), "app"))
                .isInstanceOf(BadRequestException.class);

        // Request > Limit
        assertThatThrownBy(() -> configValidator.validate(baseReq(any, 2, "600m", "500m", "128Mi", "512Mi", "1", "0", null, null, 10001L), "app"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("CPU request");
        assertThatThrownBy(() -> configValidator.validate(baseReq(any, 2, "100m", "500m", "1Gi", "512Mi", "1", "0", null, null, 10001L), "app"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Memory request");

        // Invalid resource quantity
        assertThatThrownBy(() -> configValidator.validate(baseReq(any, 2, "abc", "500m", "128Mi", "512Mi", "1", "0", null, null, 10001L), "app"))
                .isInstanceOf(BadRequestException.class);

        // Both maxSurge=0 and maxUnavailable=0
        assertThatThrownBy(() -> configValidator.validate(baseReq(any, 2, "100m", "500m", "128Mi", "512Mi", "0", "0%", null, null, 10001L), "app"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maxSurge");

        // EXEC probe rejected
        KubernetesProbeConfig execProbe = new KubernetesProbeConfig("EXEC", null, 8080, 5, 10, 3, 3, 1);
        assertThatThrownBy(() -> configValidator.validate(baseReq(any, 2, "100m", "500m", "128Mi", "512Mi", "1", "0", execProbe, null, 10001L), "app"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("EXEC");

        // Liveness earlier than readiness without startup probe rejected
        KubernetesProbeConfig ready = new KubernetesProbeConfig("HTTP_GET", "/ready", 8080, 20, 10, 3, 3, 1);
        KubernetesProbeConfig live = new KubernetesProbeConfig("HTTP_GET", "/live", 8080, 5, 10, 3, 3, 1);
        assertThatThrownBy(() -> configValidator.validate(baseReq(any, 2, "100m", "500m", "128Mi", "512Mi", "1", "0", ready, live, 10001L), "app"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Liveness probe");

        // Root UID 0 rejected
        assertThatThrownBy(() -> configValidator.validate(baseReq(any, 2, "100m", "500m", "128Mi", "512Mi", "1", "0", null, null, 0L), "app"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("non-root");

        // Valid config succeeds with deterministic checksum
        var validated = configValidator.validate(baseReq(any, 2, "100m", "500m", "128Mi", "512Mi", "1", "0", null, null, 10001L), "payments-api");
        assertThat(validated.deploymentName()).isEqualTo("payments-api");
        assertThat(validated.serviceName()).isEqualTo("payments-api");
        assertThat(validated.configChecksumSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void manifestGeneratorProducesDeterministicHardenedDeploymentAndClusterIpService() throws Exception {
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        UUID cfgId = UUID.randomUUID();
        UUID artId = UUID.randomUUID();

        KubernetesManifestGenerator.ManifestInput input = new KubernetesManifestGenerator.ManifestInput(
                wsId, projId, envId, "DEVELOPMENT", cfgId, artId,
                "cf-ws01-payments-dev", "cloudforge-workload", null,
                "payments-api", "payments-api",
                "127.0.0.1:5000/cloudforge/payments@" + VALID_DIGEST,
                VALID_DIGEST,
                "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                2, 8080, 80,
                "100m", "500m", "128Mi", "512Mi",
                KubernetesProbeConfig.defaultReadiness(8080),
                KubernetesProbeConfig.defaultLiveness(8080),
                null, "1", "0", 300,
                true, true, 10001L
        );

        var bundle1 = manifestGenerator.generate(input);
        var bundle2 = manifestGenerator.generate(input);
        assertThat(bundle1.manifestChecksumSha256()).isEqualTo(bundle2.manifestChecksumSha256());
        assertThat(bundle1.deploymentJson()).isEqualTo(bundle2.deploymentJson());

        JsonNode dep = objectMapper.readTree(bundle1.deploymentJson());
        assertThat(dep.at("/apiVersion").asText()).isEqualTo("apps/v1");
        assertThat(dep.at("/kind").asText()).isEqualTo("Deployment");
        assertThat(dep.at("/metadata/labels/app.kubernetes.io~1managed-by").asText()).isEqualTo("cloudforge");
        assertThat(dep.at("/metadata/labels/cloudforge.io~1workspace-id").asText()).isEqualTo(wsId.toString());
        assertThat(dep.at("/spec/template/spec/automountServiceAccountToken").asBoolean()).isFalse();
        assertThat(dep.at("/spec/template/spec/hostNetwork").asBoolean()).isFalse();
        assertThat(dep.at("/spec/template/spec/hostPID").asBoolean()).isFalse();
        assertThat(dep.at("/spec/template/spec/hostIPC").asBoolean()).isFalse();
        assertThat(dep.at("/spec/template/spec/securityContext/seccompProfile/type").asText()).isEqualTo("RuntimeDefault");
        assertThat(dep.at("/spec/template/spec/containers/0/image").asText()).endsWith("@" + VALID_DIGEST);
        assertThat(dep.at("/spec/template/spec/containers/0/securityContext/privileged").asBoolean()).isFalse();
        assertThat(dep.at("/spec/template/spec/containers/0/securityContext/allowPrivilegeEscalation").asBoolean()).isFalse();
        assertThat(dep.at("/spec/template/spec/containers/0/securityContext/capabilities/drop/0").asText()).isEqualTo("ALL");
        assertThat(dep.at("/spec/template/spec/containers/0/securityContext/readOnlyRootFilesystem").asBoolean()).isTrue();

        JsonNode svc = objectMapper.readTree(bundle1.serviceJson());
        assertThat(svc.at("/apiVersion").asText()).isEqualTo("v1");
        assertThat(svc.at("/kind").asText()).isEqualTo("Service");
        assertThat(svc.at("/spec/type").asText()).isEqualTo("ClusterIP");

        // Mutable tag rejected
        KubernetesManifestGenerator.ManifestInput mutableTagInput = new KubernetesManifestGenerator.ManifestInput(
                wsId, projId, envId, "DEVELOPMENT", cfgId, artId,
                "cf-ws01-payments-dev", "cloudforge-workload", null,
                "payments-api", "payments-api",
                "127.0.0.1:5000/cloudforge/payments:latest",
                VALID_DIGEST,
                "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                2, 8080, 80,
                "100m", "500m", "128Mi", "512Mi",
                KubernetesProbeConfig.defaultReadiness(8080),
                KubernetesProbeConfig.defaultLiveness(8080),
                null, "1", "0", 300,
                true, true, 10001L
        );
        assertThatThrownBy(() -> manifestGenerator.generate(mutableTagInput))
                .isInstanceOf(BadRequestException.class);
    }

    private static CreateDeploymentRequest baseReq(
            UUID id, int replicas, String cpuReq, String cpuLim, String memReq, String memLim,
            String maxSurge, String maxUnavail, KubernetesProbeConfig ready, KubernetesProbeConfig live, long uid
    ) {
        return new CreateDeploymentRequest(
                id, id, id, id, null, id,
                null, null, replicas, 8080, 80,
                cpuReq, cpuLim, memReq, memLim,
                ready, live, null,
                maxSurge, maxUnavail, 300, 300,
                true, true, uid, null, null, null
        );
    }
}
