package com.cloudforge.backend.worker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerSchedulingConfig {

    @Configuration
    @ConditionalOnProperty(
            prefix = "cloudforge.workers",
            name = {"enabled", "scheduler-enabled"},
            havingValue = "true"
    )
    @EnableScheduling
    static class ActiveWorkerSchedulerMarker {
    }
}
