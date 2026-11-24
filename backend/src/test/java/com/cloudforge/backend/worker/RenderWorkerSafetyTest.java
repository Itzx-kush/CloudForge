package com.cloudforge.backend.worker;

import com.cloudforge.backend.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies deployment safety default: worker scheduling and internal worker endpoints
 * are disabled by default (including under Render profile) and fail closed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RenderWorkerSafetyTest {

    @Autowired
    private WorkerProperties workerProperties;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void workerExecutionPlaneIsDisabledByDefaultAndInternalEndpointsFailClosed() throws Exception {
        assertThat(workerProperties.isEnabled()).isFalse();
        assertThat(workerProperties.isSchedulerEnabled()).isFalse();

        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", "rogue-worker")
                        .header("Authorization", "Bearer some-long-unprovisioned-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "rogue-worker",
                                  "displayName": "Rogue",
                                  "adapterType": "DOCKER_ISOLATED",
                                  "supportedOperations": ["ENV_VALIDATION"],
                                  "maxConcurrency": 1
                                }
                                """))
                .andExpect(status().isServiceUnavailable());
    }
}
