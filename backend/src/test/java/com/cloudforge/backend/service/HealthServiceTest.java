package com.cloudforge.backend.service;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.dto.HealthResponse;
import com.cloudforge.backend.repository.DatabaseProbeRepository;
import com.cloudforge.backend.repository.DatabaseProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-23T06:30:00Z");

    @Mock
    private DatabaseProbeRepository databaseProbeRepository;

    private HealthService healthService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.setName("CloudForge Backend");
        properties.setVersion("0.1.0-phase1");
        properties.setPhase("Phase 1 — Platform Foundation");
        healthService = new HealthService(
                properties,
                databaseProbeRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
        );
    }

    @Test
    void checkHealthReturnsUpWhenDatabaseProbeSucceeds() {
        when(databaseProbeRepository.probe()).thenReturn(
                new DatabaseProbeResult(true, "PostgreSQL 17.4", 5L, "Database connection verified")
        );

        HealthResponse response = healthService.checkHealth();

        assertThat(healthService.isHealthy(response)).isTrue();
        assertThat(response.application()).isEqualTo("CloudForge Backend");
        assertThat(response.status()).isEqualTo(HealthService.STATUS_UP);
        assertThat(response.database().status()).isEqualTo(HealthService.STATUS_UP);
        assertThat(response.database().engine()).isEqualTo("PostgreSQL 17.4");
        assertThat(response.database().responseTimeMs()).isEqualTo(5L);
        assertThat(response.timestamp()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void checkHealthReturnsDegradedWhenDatabaseProbeFails() {
        when(databaseProbeRepository.probe()).thenReturn(
                new DatabaseProbeResult(false, "PostgreSQL", 3000L, "Database connectivity check failed")
        );

        HealthResponse response = healthService.checkHealth();

        assertThat(healthService.isHealthy(response)).isFalse();
        assertThat(response.status()).isEqualTo(HealthService.STATUS_DEGRADED);
        assertThat(response.database().status()).isEqualTo(HealthService.STATUS_DOWN);
        assertThat(response.database().message()).isEqualTo("Database connectivity check failed");
        assertThat(response.timestamp()).isEqualTo(FIXED_INSTANT);
    }
}
