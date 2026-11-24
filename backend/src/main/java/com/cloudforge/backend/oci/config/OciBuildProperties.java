package com.cloudforge.backend.oci.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-closed configuration properties for Phase 3.5B OCI container builds,
 * verified source/OCI handoffs, and OCI registry publishing.
 *
 * <p>All build execution and local registry override flags default to {@code false}
 * and are explicitly locked to {@code false} in {@code application-render.yml}.</p>
 */
@Component
@ConfigurationProperties(prefix = "cloudforge.oci")
public class OciBuildProperties {

    private boolean enabled = false;
    private boolean allowLocalDevRegistry = false;
    private boolean allowInsecureLocalCredentialDelivery = false;
    private String allowedRegistryHosts = "";
    private String allowedWorkerIds = "";
    private String allowedPlatforms = "linux/amd64,linux/arm64";
    private String handoffStorageDir = Path.of(System.getProperty("java.io.tmpdir"), "cloudforge-oci-handoff").toString();
    private long maxSourceHandoffBytes = 32L * 1024 * 1024;
    private long maxOciLayoutHandoffBytes = 256L * 1024 * 1024;
    private long maxTotalHandoffStorageBytes = 1024L * 1024 * 1024;
    private int handoffTtlSeconds = 1800;
    private int maxBuildTimeoutSeconds = 900;
    private int maxCredentialDeliveriesPerAttempt = 2;
    private long handoffCleanupIntervalMs = 300_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowLocalDevRegistry() {
        return allowLocalDevRegistry;
    }

    public void setAllowLocalDevRegistry(boolean allowLocalDevRegistry) {
        this.allowLocalDevRegistry = allowLocalDevRegistry;
    }

    public boolean isAllowInsecureLocalCredentialDelivery() {
        return allowInsecureLocalCredentialDelivery;
    }

    public void setAllowInsecureLocalCredentialDelivery(boolean allowInsecureLocalCredentialDelivery) {
        this.allowInsecureLocalCredentialDelivery = allowInsecureLocalCredentialDelivery;
    }

    public String getAllowedRegistryHosts() {
        return allowedRegistryHosts;
    }

    public void setAllowedRegistryHosts(String allowedRegistryHosts) {
        this.allowedRegistryHosts = allowedRegistryHosts == null ? "" : allowedRegistryHosts;
    }

    public String getAllowedWorkerIds() {
        return allowedWorkerIds;
    }

    public void setAllowedWorkerIds(String allowedWorkerIds) {
        this.allowedWorkerIds = allowedWorkerIds == null ? "" : allowedWorkerIds;
    }

    public String getAllowedPlatforms() {
        return allowedPlatforms;
    }

    public void setAllowedPlatforms(String allowedPlatforms) {
        this.allowedPlatforms = allowedPlatforms == null ? "" : allowedPlatforms;
    }

    public String getHandoffStorageDir() {
        return handoffStorageDir;
    }

    public void setHandoffStorageDir(String handoffStorageDir) {
        this.handoffStorageDir = handoffStorageDir;
    }

    public long getMaxSourceHandoffBytes() {
        return maxSourceHandoffBytes;
    }

    public void setMaxSourceHandoffBytes(long maxSourceHandoffBytes) {
        this.maxSourceHandoffBytes = maxSourceHandoffBytes;
    }

    public long getMaxOciLayoutHandoffBytes() {
        return maxOciLayoutHandoffBytes;
    }

    public void setMaxOciLayoutHandoffBytes(long maxOciLayoutHandoffBytes) {
        this.maxOciLayoutHandoffBytes = maxOciLayoutHandoffBytes;
    }

    public long getMaxTotalHandoffStorageBytes() {
        return maxTotalHandoffStorageBytes;
    }

    public void setMaxTotalHandoffStorageBytes(long maxTotalHandoffStorageBytes) {
        this.maxTotalHandoffStorageBytes = maxTotalHandoffStorageBytes;
    }

    public int getHandoffTtlSeconds() {
        return handoffTtlSeconds;
    }

    public void setHandoffTtlSeconds(int handoffTtlSeconds) {
        this.handoffTtlSeconds = handoffTtlSeconds;
    }

    public int getMaxBuildTimeoutSeconds() {
        return maxBuildTimeoutSeconds;
    }

    public void setMaxBuildTimeoutSeconds(int maxBuildTimeoutSeconds) {
        this.maxBuildTimeoutSeconds = maxBuildTimeoutSeconds;
    }

    public int getMaxCredentialDeliveriesPerAttempt() {
        return maxCredentialDeliveriesPerAttempt;
    }

    public void setMaxCredentialDeliveriesPerAttempt(int maxCredentialDeliveriesPerAttempt) {
        this.maxCredentialDeliveriesPerAttempt = maxCredentialDeliveriesPerAttempt;
    }

    public long getHandoffCleanupIntervalMs() {
        return handoffCleanupIntervalMs;
    }

    public void setHandoffCleanupIntervalMs(long handoffCleanupIntervalMs) {
        this.handoffCleanupIntervalMs = handoffCleanupIntervalMs;
    }

    public Set<String> parsedAllowedRegistryHosts() {
        return parseCsvLower(allowedRegistryHosts);
    }

    public Set<String> parsedAllowedWorkerIds() {
        if (allowedWorkerIds == null || allowedWorkerIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedWorkerIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> parsedAllowedPlatforms() {
        return parseCsvLower(allowedPlatforms);
    }

    public boolean permitsBuildWorker(String workerId) {
        if (!enabled || workerId == null || workerId.isBlank()) {
            return false;
        }
        Set<String> allowlist = parsedAllowedWorkerIds();
        return !allowlist.isEmpty() && allowlist.contains(workerId.trim());
    }

    private static Set<String> parseCsvLower(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
