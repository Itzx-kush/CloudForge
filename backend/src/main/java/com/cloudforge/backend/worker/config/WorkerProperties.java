package com.cloudforge.backend.worker.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for CloudForge Phase 3.3 worker control plane.
 *
 * <p><strong>Deployment safety default:</strong> Both {@code enabled} and
 * {@code schedulerEnabled} default to {@code false} so existing Render web services
 * and development environments without a trusted worker plane fail closed.</p>
 */
@Validated
@ConfigurationProperties(prefix = "cloudforge.workers")
public class WorkerProperties {

    private boolean enabled = false;

    private boolean schedulerEnabled = false;

    /**
     * Comma-separated provisioned worker credentials supplied strictly via environment
     * variables (never committed to source control).
     * Format: {@code workerId:keyId:secret} or {@code workerId:keyId:secret:expiresAtIso}
     */
    private String credentials = "";

    @Min(10)
    @Max(600)
    private int leaseDurationSeconds = 60;

    @Min(5)
    @Max(300)
    private int heartbeatIntervalSeconds = 10;

    @Min(15)
    @Max(600)
    private int heartbeatTimeoutSeconds = 45;

    @Min(1000)
    @Max(300000)
    private long recoveryIntervalMs = 15000L;

    @Min(1)
    @Max(50)
    private int maxClaimBatchSize = 10;

    @Min(1)
    @Max(100)
    private int maxRecoveryBatchSize = 25;

    @Min(1)
    @Max(300)
    private int retryBaseBackoffSeconds = 5;

    @Min(5)
    @Max(120)
    private int cancelGracePeriodSeconds = 15;

    @Min(10)
    @Max(2000)
    private int maxLogLinesPerAttempt = 250;

    @Min(1024)
    @Max(524288)
    private int maxLogBytesPerAttempt = 65536;

    @Min(128)
    @Max(2000)
    private int maxLogMessageLength = 1800;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public int getLeaseDurationSeconds() {
        return leaseDurationSeconds;
    }

    public void setLeaseDurationSeconds(int leaseDurationSeconds) {
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public int getHeartbeatTimeoutSeconds() {
        return heartbeatTimeoutSeconds;
    }

    public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    public long getRecoveryIntervalMs() {
        return recoveryIntervalMs;
    }

    public void setRecoveryIntervalMs(long recoveryIntervalMs) {
        this.recoveryIntervalMs = recoveryIntervalMs;
    }

    public int getMaxClaimBatchSize() {
        return maxClaimBatchSize;
    }

    public void setMaxClaimBatchSize(int maxClaimBatchSize) {
        this.maxClaimBatchSize = maxClaimBatchSize;
    }

    public int getMaxRecoveryBatchSize() {
        return maxRecoveryBatchSize;
    }

    public void setMaxRecoveryBatchSize(int maxRecoveryBatchSize) {
        this.maxRecoveryBatchSize = maxRecoveryBatchSize;
    }

    public int getRetryBaseBackoffSeconds() {
        return retryBaseBackoffSeconds;
    }

    public void setRetryBaseBackoffSeconds(int retryBaseBackoffSeconds) {
        this.retryBaseBackoffSeconds = retryBaseBackoffSeconds;
    }

    public int getCancelGracePeriodSeconds() {
        return cancelGracePeriodSeconds;
    }

    public void setCancelGracePeriodSeconds(int cancelGracePeriodSeconds) {
        this.cancelGracePeriodSeconds = cancelGracePeriodSeconds;
    }

    public int getMaxLogLinesPerAttempt() {
        return maxLogLinesPerAttempt;
    }

    public void setMaxLogLinesPerAttempt(int maxLogLinesPerAttempt) {
        this.maxLogLinesPerAttempt = maxLogLinesPerAttempt;
    }

    public int getMaxLogBytesPerAttempt() {
        return maxLogBytesPerAttempt;
    }

    public void setMaxLogBytesPerAttempt(int maxLogBytesPerAttempt) {
        this.maxLogBytesPerAttempt = maxLogBytesPerAttempt;
    }

    public int getMaxLogMessageLength() {
        return maxLogMessageLength;
    }

    public void setMaxLogMessageLength(int maxLogMessageLength) {
        this.maxLogMessageLength = maxLogMessageLength;
    }
}
