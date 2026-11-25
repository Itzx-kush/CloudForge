package com.cloudforge.backend.k8s.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Validated declarative health probe configuration for Kubernetes containers.
 * Supports HTTP_GET (approved application path) and TCP_SOCKET probes.
 */
public record KubernetesProbeConfig(
        @NotBlank @Size(max = 16) String type,
        @Size(max = 200) String path,
        @Min(1) @Max(65535) Integer port,
        @Min(0) @Max(600) Integer initialDelaySeconds,
        @Min(2) @Max(300) Integer periodSeconds,
        @Min(1) @Max(60) Integer timeoutSeconds,
        @Min(1) @Max(20) Integer failureThreshold,
        @Min(1) @Max(10) Integer successThreshold
) {
    public static KubernetesProbeConfig defaultReadiness(int containerPort) {
        return new KubernetesProbeConfig("TCP_SOCKET", null, containerPort, 5, 5, 3, 3, 1);
    }

    public static KubernetesProbeConfig defaultLiveness(int containerPort) {
        return new KubernetesProbeConfig("TCP_SOCKET", null, containerPort, 15, 10, 3, 3, 1);
    }
}
