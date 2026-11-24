package com.cloudforge.backend.oci;

import com.cloudforge.backend.oci.config.OciBuildProperties;
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
 * Verifies Phase 3.5B Render / production deployment safety guarantees:
 * <ul>
 *   <li>OCI execution, local dev registries, and insecure local credential delivery default to {@code false}.</li>
 *   <li>{@code application-render.yml} explicitly locks all OCI/worker execution flags to {@code false}.</li>
 *   <li>Internal worker OCI endpoints fail closed ({@code 503}) when workers/OCI are disabled.</li>
 *   <li>The Spring Boot backend contains zero Docker socket or direct BuildKit execution invocations.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RenderOciSafetyTest {

    @Autowired
    private OciBuildProperties ociProperties;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ociExecutionAndLocalDevOverridesDefaultToDisabledAndInternalEndpointsFailClosed() throws Exception {
        assertThat(ociProperties.isEnabled()).isFalse();
        assertThat(ociProperties.isAllowLocalDevRegistry()).isFalse();
        assertThat(ociProperties.isAllowInsecureLocalCredentialDelivery()).isFalse();
        assertThat(ociProperties.parsedAllowedWorkerIds()).isEmpty();
        assertThat(ociProperties.parsedAllowedRegistryHosts()).isEmpty();

        String attemptId = "00000000-0000-0000-0000-000000000010";
        String leaseJson = "{\"leaseToken\":\"fake-lease\",\"fencingGeneration\":1}";

        for (String endpoint : List.of(
                "/api/internal/workers/rogue-oci/attempts/" + attemptId + "/oci-descriptor",
                "/api/internal/workers/rogue-oci/attempts/" + attemptId + "/build-context",
                "/api/internal/workers/rogue-oci/attempts/" + attemptId + "/oci-layout-download",
                "/api/internal/workers/rogue-oci/attempts/" + attemptId + "/registry-credentials"
        )) {
            mockMvc.perform(post(endpoint)
                            .header("X-CloudForge-Worker-Id", "rogue-oci")
                            .header("Authorization", "Bearer unprovisioned-worker-token-00000000")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(leaseJson))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    @Test
    void renderProfileExplicitlyLocksOciAndWorkerFlagsDisabledAndBackendHasNoDockerExecution() throws Exception {
        Path renderYaml = Path.of("src/main/resources/application-render.yml");
        assertThat(renderYaml).exists();
        String yaml = Files.readString(renderYaml, StandardCharsets.UTF_8);
        assertThat(yaml).contains("oci:");
        assertThat(yaml).contains("allow-local-dev-registry: false");
        assertThat(yaml).contains("allow-insecure-local-credential-delivery: false");
        assertThat(yaml).contains("scheduler-enabled: false");

        Path mainJavaRoot = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(mainJavaRoot)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(javaFiles).isNotEmpty();
            for (Path file : javaFiles) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(source)
                        .as("Backend source %s must never invoke Docker socket or BuildKit CLI directly", file)
                        .doesNotContain("/var/run/docker.sock")
                        .doesNotContain("new ProcessBuilder(\"docker\"")
                        .doesNotContain("new ProcessBuilder(\"buildctl\"");
            }
        }
    }
}
