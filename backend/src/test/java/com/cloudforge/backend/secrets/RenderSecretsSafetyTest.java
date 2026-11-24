package com.cloudforge.backend.secrets;

import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies deployment fail-closed defaults for Phase 3.4 secret management, worker secret delivery,
 * insecure local transport override, operator key rotation, and zero secret/key disclosure on /api/health.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RenderSecretsSafetyTest {

    @Autowired
    private SecretManagementProperties secretProperties;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void secretDeliveryAndOperatorRotationDefaultToDisabledAndHealthExposesZeroKeyMaterial() throws Exception {
        assertThat(secretProperties.isEnabled()).isFalse();
        assertThat(secretProperties.isWorkerDeliveryEnabled()).isFalse();
        assertThat(secretProperties.isAllowInsecureLocalDelivery()).isFalse();
        assertThat(secretProperties.isOperatorRotationEnabled()).isFalse();

        MvcResult healthResult = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn();
        String healthJson = healthResult.getResponse().getContentAsString();
        assertThat(healthJson)
                .doesNotContain("keyring")
                .doesNotContain("kek")
                .doesNotContain("secretCount")
                .doesNotContain("keyCount");

        mockMvc.perform(post("/api/internal/operator/secrets/keys/rotate")
                        .header("Authorization", "Bearer unprovisioned-operator-token-000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/internal/workers/worker-1/attempts/00000000-0000-0000-0000-000000000001/secrets")
                        .header("X-CloudForge-Worker-Id", "worker-1")
                        .header("Authorization", "Bearer unprovisioned-worker-token-000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseToken\":\"t\",\"fencingGeneration\":1}"))
                .andExpect(status().isServiceUnavailable());
    }
}
