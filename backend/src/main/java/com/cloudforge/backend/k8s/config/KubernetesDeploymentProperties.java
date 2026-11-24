package com.cloudforge.backend.k8s.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-closed configuration properties for Phase 3.6 Kubernetes Deployment Engine.
 *
 * <p>All deployment execution, local cluster loopback, insecure local credential delivery,
 * and production deployment flags default to {@code false} and are explicitly locked
 * to {@code false} in {@code application-render.yml}.</p>
 */
@Component
@ConfigurationProperties(prefix = "cloudforge.kubernetes")
public class KubernetesDeploymentProperties {

    private boolean enabled = false;
    private boolean allowLocalDevClusters = false;
    private boolean allowInsecureLocalCredentialDelivery = false;
    private boolean allowProductionDeployments = false;
    private String allowedApiHosts = "";
    private String allowedWorkerIds = "";
    private int maxDeploymentTimeoutSeconds = 900;
    private int maxReplicas = 20;
    private int maxCredentialDeliveriesPerAttempt = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowLocalDevClusters() {
        return allowLocalDevClusters;
    }

    public void setAllowLocalDevClusters(boolean allowLocalDevClusters) {
        this.allowLocalDevClusters = allowLocalDevClusters;
    }

    public boolean isAllowInsecureLocalCredentialDelivery() {
        return allowInsecureLocalCredentialDelivery;
    }

    public void setAllowInsecureLocalCredentialDelivery(boolean allowInsecureLocalCredentialDelivery) {
        this.allowInsecureLocalCredentialDelivery = allowInsecureLocalCredentialDelivery;
    }

    public boolean isAllowProductionDeployments() {
        return allowProductionDeployments;
    }

    public void setAllowProductionDeployments(boolean allowProductionDeployments) {
        this.allowProductionDeployments = allowProductionDeployments;
    }

    public String getAllowedApiHosts() {
        return allowedApiHosts;
    }

    public void setAllowedApiHosts(String allowedApiHosts) {
        this.allowedApiHosts = allowedApiHosts == null ? "" : allowedApiHosts;
    }

    public String getAllowedWorkerIds() {
        return allowedWorkerIds;
    }

    public void setAllowedWorkerIds(String allowedWorkerIds) {
        this.allowedWorkerIds = allowedWorkerIds == null ? "" : allowedWorkerIds;
    }

    public int getMaxDeploymentTimeoutSeconds() {
        return maxDeploymentTimeoutSeconds;
    }

    public void setMaxDeploymentTimeoutSeconds(int maxDeploymentTimeoutSeconds) {
        this.maxDeploymentTimeoutSeconds = maxDeploymentTimeoutSeconds;
    }

    public int getMaxReplicas() {
        return maxReplicas;
    }

    public void setMaxReplicas(int maxReplicas) {
        this.maxReplicas = maxReplicas;
    }

    public int getMaxCredentialDeliveriesPerAttempt() {
        return maxCredentialDeliveriesPerAttempt;
    }

    public void setMaxCredentialDeliveriesPerAttempt(int maxCredentialDeliveriesPerAttempt) {
        this.maxCredentialDeliveriesPerAttempt = maxCredentialDeliveriesPerAttempt;
    }

    public Set<String> parsedAllowedApiHosts() {
        if (allowedApiHosts == null || allowedApiHosts.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedApiHosts.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
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

    public boolean permitsDeployWorker(String workerId) {
        if (!enabled || workerId == null || workerId.isBlank()) {
            return false;
        }
        Set<String> allowlist = parsedAllowedWorkerIds();
        return !allowlist.isEmpty() && allowlist.contains(workerId.trim());
    }
}
