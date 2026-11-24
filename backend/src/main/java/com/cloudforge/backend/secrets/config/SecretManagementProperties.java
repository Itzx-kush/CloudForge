package com.cloudforge.backend.secrets.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for CloudForge Phase 3.4 encrypted secret management,
 * externally provisioned master keyrings, and lease-bound worker delivery.
 *
 * <p><strong>Fail-closed defaults:</strong> Secret management and worker secret delivery
 * default to {@code false} until valid external encryption keys are explicitly configured.
 * Master keys must never be generated automatically at runtime or persisted in PostgreSQL.</p>
 */
@Validated
@ConfigurationProperties(prefix = "cloudforge.secrets")
public class SecretManagementProperties {

    private boolean enabled = false;

    @NotBlank
    private String keyProvider = "env-keyring";

    private String activeKeyId = "";

    /**
     * Comma-separated key-encryption key entries supplied via environment variables:
     * {@code keyId1:base64Encoded256BitKey,keyId2:base64Encoded256BitKey}.
     * Never logged or exposed over any API.
     */
    private String keyring = "";

    @Min(128)
    @Max(65536)
    private int maxSecretValueBytes = 16384;

    @Min(1)
    @Max(500)
    private int maxSecretsPerEnvironment = 100;

    @Min(1)
    @Max(50)
    private int maxBindingsPerPipeline = 25;

    /**
     * Explicit opt-in required to deliver decrypted secrets to workers over loopback HTTP
     * during local development. Production/Render must keep this {@code false}.
     */
    private boolean allowInsecureLocalDelivery = false;

    /**
     * Controls whether authenticated trusted workers can retrieve bound secrets during active leases.
     * Kept {@code false} by default until supported secret-consuming operations are enabled.
     */
    private boolean workerDeliveryEnabled = false;

    @Min(1)
    @Max(10)
    private int maxDeliveriesPerAttempt = 2;

    @Min(0)
    @Max(3600)
    private int deniedAuditCooldownSeconds = 10;

    private boolean operatorRotationEnabled = false;

    private String operatorRotationToken = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyProvider() {
        return keyProvider;
    }

    public void setKeyProvider(String keyProvider) {
        this.keyProvider = keyProvider;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    public String getKeyring() {
        return keyring;
    }

    public void setKeyring(String keyring) {
        this.keyring = keyring;
    }

    public int getMaxSecretValueBytes() {
        return maxSecretValueBytes;
    }

    public void setMaxSecretValueBytes(int maxSecretValueBytes) {
        this.maxSecretValueBytes = maxSecretValueBytes;
    }

    public int getMaxSecretsPerEnvironment() {
        return maxSecretsPerEnvironment;
    }

    public void setMaxSecretsPerEnvironment(int maxSecretsPerEnvironment) {
        this.maxSecretsPerEnvironment = maxSecretsPerEnvironment;
    }

    public int getMaxBindingsPerPipeline() {
        return maxBindingsPerPipeline;
    }

    public void setMaxBindingsPerPipeline(int maxBindingsPerPipeline) {
        this.maxBindingsPerPipeline = maxBindingsPerPipeline;
    }

    public boolean isAllowInsecureLocalDelivery() {
        return allowInsecureLocalDelivery;
    }

    public void setAllowInsecureLocalDelivery(boolean allowInsecureLocalDelivery) {
        this.allowInsecureLocalDelivery = allowInsecureLocalDelivery;
    }

    public boolean isWorkerDeliveryEnabled() {
        return workerDeliveryEnabled;
    }

    public void setWorkerDeliveryEnabled(boolean workerDeliveryEnabled) {
        this.workerDeliveryEnabled = workerDeliveryEnabled;
    }

    public int getMaxDeliveriesPerAttempt() {
        return maxDeliveriesPerAttempt;
    }

    public void setMaxDeliveriesPerAttempt(int maxDeliveriesPerAttempt) {
        this.maxDeliveriesPerAttempt = maxDeliveriesPerAttempt;
    }

    public int getDeniedAuditCooldownSeconds() {
        return deniedAuditCooldownSeconds;
    }

    public void setDeniedAuditCooldownSeconds(int deniedAuditCooldownSeconds) {
        this.deniedAuditCooldownSeconds = deniedAuditCooldownSeconds;
    }

    public boolean isOperatorRotationEnabled() {
        return operatorRotationEnabled;
    }

    public void setOperatorRotationEnabled(boolean operatorRotationEnabled) {
        this.operatorRotationEnabled = operatorRotationEnabled;
    }

    public String getOperatorRotationToken() {
        return operatorRotationToken;
    }

    public void setOperatorRotationToken(String operatorRotationToken) {
        this.operatorRotationToken = operatorRotationToken;
    }
}
