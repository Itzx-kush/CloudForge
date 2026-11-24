package com.cloudforge.backend.secrets.dto;

public record SecretStatusResponse(
        boolean enabled,
        boolean available,
        String providerType,
        String activeKeyId,
        boolean workerDeliveryEnabled,
        boolean allowInsecureLocalDelivery,
        boolean operatorRotationEnabled,
        int maxSecretValueBytes,
        int maxSecretsPerEnvironment,
        int maxBindingsPerPipeline,
        String statusDiagnostic
) {
}
