package com.cloudforge.backend.service;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.dto.DatabaseHealthDto;
import com.cloudforge.backend.dto.HealthResponse;
import com.cloudforge.backend.repository.DatabaseProbeRepository;
import com.cloudforge.backend.repository.DatabaseProbeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class HealthService {

    public static final String STATUS_UP = "UP";
    public static final String STATUS_DOWN = "DOWN";
    public static final String STATUS_DEGRADED = "DEGRADED";

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final AppProperties appProperties;
    private final DatabaseProbeRepository databaseProbeRepository;
    private final Clock clock;

    @Autowired
    public HealthService(
            AppProperties appProperties,
            DatabaseProbeRepository databaseProbeRepository
    ) {
        this(appProperties, databaseProbeRepository, Clock.systemUTC());
    }

    HealthService(
            AppProperties appProperties,
            DatabaseProbeRepository databaseProbeRepository,
            Clock clock
    ) {
        this.appProperties = appProperties;
        this.databaseProbeRepository = databaseProbeRepository;
        this.clock = clock;
    }

    public HealthResponse checkHealth() {
        DatabaseProbeResult probe = databaseProbeRepository.probe();
        boolean databaseHealthy = probe.connected();

        String applicationStatus = databaseHealthy ? STATUS_UP : STATUS_DEGRADED;
        String databaseStatus = databaseHealthy ? STATUS_UP : STATUS_DOWN;

        DatabaseHealthDto databaseHealth = new DatabaseHealthDto(
                databaseStatus,
                probe.engine(),
                probe.responseTimeMs(),
                probe.message()
        );

        HealthResponse response = new HealthResponse(
                appProperties.getName(),
                appProperties.getVersion(),
                appProperties.getPhase(),
                applicationStatus,
                databaseHealth,
                Instant.now(clock)
        );

        if (databaseHealthy) {
            log.info(
                    "Health check status={} database={} engine=\"{}\" responseTimeMs={}",
                    applicationStatus,
                    databaseStatus,
                    probe.engine(),
                    probe.responseTimeMs()
            );
        } else {
            log.warn(
                    "Health check status={} database={} responseTimeMs={} reason=\"{}\"",
                    applicationStatus,
                    databaseStatus,
                    probe.responseTimeMs(),
                    probe.message()
            );
        }

        return response;
    }

    public boolean isHealthy(HealthResponse response) {
        return STATUS_UP.equals(response.status())
                && response.database() != null
                && STATUS_UP.equals(response.database().status());
    }
}
