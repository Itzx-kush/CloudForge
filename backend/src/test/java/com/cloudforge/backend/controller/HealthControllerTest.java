package com.cloudforge.backend.controller;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.dto.DatabaseHealthDto;
import com.cloudforge.backend.dto.HealthResponse;
import com.cloudforge.backend.exception.GlobalExceptionHandler;
import com.cloudforge.backend.service.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@EnableConfigurationProperties(AppProperties.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    void returnsOkWhenApplicationAndDatabaseAreUp() throws Exception {
        HealthResponse response = new HealthResponse(
                "CloudForge Backend",
                "1.0.0",
                "Phases 1-4 - DevOps Control Plane, Orchestration, Delivery & Observability",
                HealthService.STATUS_UP,
                new DatabaseHealthDto("UP", "PostgreSQL 17.4", 4L, "Database connection verified"),
                Instant.parse("2026-11-23T06:00:00Z")
        );

        when(healthService.checkHealth()).thenReturn(response);
        when(healthService.isHealthy(response)).thenReturn(true);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("CloudForge Backend"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.engine").value("PostgreSQL 17.4"))
                .andExpect(jsonPath("$.database.responseTimeMs").value(4))
                .andExpect(jsonPath("$.timestamp").value("2026-11-23T06:00:00Z"));
    }

    @Test
    void returnsServiceUnavailableWhenDatabaseIsDown() throws Exception {
        HealthResponse response = new HealthResponse(
                "CloudForge Backend",
                "1.0.0",
                "Phases 1-4 - DevOps Control Plane, Orchestration, Delivery & Observability",
                HealthService.STATUS_DEGRADED,
                new DatabaseHealthDto("DOWN", "PostgreSQL", 3001L, "Database connectivity check failed"),
                Instant.parse("2026-11-23T06:05:00Z")
        );

        when(healthService.checkHealth()).thenReturn(response);
        when(healthService.isHealthy(response)).thenReturn(false);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.application").value("CloudForge Backend"))
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.database.status").value("DOWN"))
                .andExpect(jsonPath("$.database.message").value("Database connectivity check failed"));
    }

    @Test
    void returnsMethodNotAllowedForPostOnHealthEndpoint() throws Exception {
        mockMvc.perform(post("/api/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }
}
