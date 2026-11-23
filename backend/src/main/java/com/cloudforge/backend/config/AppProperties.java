package com.cloudforge.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "cloudforge")
public class AppProperties {

    @NotBlank
    private String name = "CloudForge Backend";

    @NotBlank
    private String version = "0.1.0-phase1";

    @NotBlank
    private String phase = "Phase 1 — Platform Foundation";

    @Min(1)
    @Max(30)
    private int databaseCheckTimeoutSeconds = 3;

    private final Cors cors = new Cors();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public int getDatabaseCheckTimeoutSeconds() {
        return databaseCheckTimeoutSeconds;
    }

    public void setDatabaseCheckTimeoutSeconds(int databaseCheckTimeoutSeconds) {
        this.databaseCheckTimeoutSeconds = databaseCheckTimeoutSeconds;
    }

    public Cors getCors() {
        return cors;
    }

    public static class Cors {

        @NotEmpty
        private List<String> allowedOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173");

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }
}
