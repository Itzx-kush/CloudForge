package com.cloudforge.backend.k8s;

import com.cloudforge.backend.k8s.config.KubernetesDeploymentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies Phase 3.6 Render / public control-plane safety guarantees:
 * <ul>
 *   <li>All Kubernetes execution, local dev cluster, production deployment, and insecure credential flags default to {@code false}.</li>
 *   <li>{@code application-render.yml} explicitly locks all Kubernetes and worker execution flags to {@code false}.</li>
 *   <li>Internal worker Kubernetes endpoints fail closed ({@code 503}) when workers/Kubernetes execution are disabled.</li>
 *   <li>The Spring Boot backend contains zero {@code kubectl} execution, kubeconfig reading, or Kubernetes API client imports.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RenderKubernetesSafetyTest {

    @Autowired
    private KubernetesDeploymentProperties k8sProperties;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void kubernetesExecutionDefaultsToDisabledAndInternalWorkerEndpointsFailClosed() throws Exception {
        assertThat(k8sProperties.isEnabled()).isFalse();
        assertThat(k8sProperties.isAllowLocalDevClusters()).isFalse();
        assertThat(k8sProperties.isAllowProductionDeployments()).isFalse();
        assertThat(k8sProperties.isAllowInsecureLocalCredentialDelivery()).isFalse();
        assertThat(k8sProperties.parsedAllowedApiHosts()).isEmpty();
        assertThat(k8sProperties.parsedAllowedWorkerIds()).isEmpty();

        String attemptId = "00000000-0000-0000-0000-000000000036";
        String clusterId = "00000000-0000-0000-0000-000000000037";
        String leaseJson = "{\"leaseToken\":\"fake-lease\",\"fencingGeneration\":1}";
        String nonceJson = "{\"validationNonce\":\"fake-nonce\"}";

        mockMvc.perform(post("/api/internal/workers/rogue-k8s/k8s/cluster-validations/claim")
                        .header("X-CloudForge-Worker-Id", "rogue-k8s")
                        .header("Authorization", "Bearer unprovisioned-worker-token-00000000"))
                .andExpect(status().isServiceUnavailable());

        for (String endpoint : List.of(
                "/api/internal/workers/rogue-k8s/attempts/" + attemptId + "/k8s-descriptor",
                "/api/internal/workers/rogue-k8s/attempts/" + attemptId + "/k8s-credentials"
        )) {
            mockMvc.perform(post(endpoint)
                            .header("X-CloudForge-Worker-Id", "rogue-k8s")
                            .header("Authorization", "Bearer unprovisioned-worker-token-00000000")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(leaseJson))
                    .andExpect(status().isServiceUnavailable());
        }

        mockMvc.perform(post("/api/internal/workers/rogue-k8s/k8s/cluster-validations/" + clusterId + "/credentials")
                        .header("X-CloudForge-Worker-Id", "rogue-k8s")
                        .header("Authorization", "Bearer unprovisioned-worker-token-00000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nonceJson))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void renderProfileLocksKubernetesDisabledAndBackendContainsZeroKubernetesClientOrKubectlCode() throws Exception {
        Path renderYaml = Path.of("src/main/resources/application-render.yml");
        assertThat(renderYaml).exists();
        String yaml = Files.readString(renderYaml, StandardCharsets.UTF_8);
        assertThat(yaml).contains("kubernetes:");
        assertThat(yaml).contains("allow-local-dev-clusters: false");
        assertThat(yaml).contains("allow-production-deployments: false");
        assertThat(yaml).contains("allow-insecure-local-credential-delivery: false");
        assertThat(yaml).contains("scheduler-enabled: false");

        Path pomXml = Path.of("pom.xml");
        assertThat(pomXml).exists();
        String pomContent = Files.readString(pomXml, StandardCharsets.UTF_8);
        assertThat(pomContent)
                .as("Backend control plane pom.xml must not depend on Kubernetes client libraries")
                .doesNotContain("io.kubernetes")
                .doesNotContain("fabric8");

        Path mainJavaRoot = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(mainJavaRoot)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(javaFiles).isNotEmpty();
            for (Path file : javaFiles) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(source)
                        .as("Backend source %s must never import Kubernetes client or execute kubectl", file)
                        .doesNotContain("import io.kubernetes.")
                        .doesNotContain("new ProcessBuilder(\"kubectl\"")
                        .doesNotContain("Runtime.getRuntime().exec")
                        .doesNotContain("ClientBuilder.standard()");
            }
        }
    }
}
