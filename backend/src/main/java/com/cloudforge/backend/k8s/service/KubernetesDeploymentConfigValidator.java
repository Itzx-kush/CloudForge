package com.cloudforge.backend.k8s.service;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.k8s.config.KubernetesDeploymentProperties;
import com.cloudforge.backend.k8s.dto.CreateDeploymentRequest;
import com.cloudforge.backend.k8s.dto.KubernetesProbeConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates declarative Kubernetes Deployment + ClusterIP Service configurations,
 * enforcing strict resource bounds, DNS-1123 workload names, safe rolling update
 * parameters, conservative health probes, and non-root/seccomp container security.
 */
@Service
public class KubernetesDeploymentConfigValidator {

    private static final Pattern DNS_1123_NAME = Pattern.compile("^[a-z](?:[-a-z0-9]{0,61}[a-z0-9])?$");
    private static final Pattern CPU_MILLI_PATTERN = Pattern.compile("^([1-9][0-9]{0,4})m$");
    private static final Pattern CPU_CORE_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]{1,2})?)$");
    private static final Pattern MEMORY_PATTERN = Pattern.compile("^([1-9][0-9]{0,5})(Mi|Gi)$");
    private static final Pattern SURGE_INT_PATTERN = Pattern.compile("^([0-9]{1,2})$");
    private static final Pattern SURGE_PCT_PATTERN = Pattern.compile("^([0-9]{1,3})%$");

    private static final int MIN_CPU_MILLIS = 10;
    private static final int MAX_CPU_MILLIS = 4000;
    private static final long MIN_MEMORY_MIB = 32L;
    private static final long MAX_MEMORY_MIB = 4096L;

    private final KubernetesDeploymentProperties k8sProperties;
    private final ObjectMapper objectMapper;

    public KubernetesDeploymentConfigValidator(
            KubernetesDeploymentProperties k8sProperties,
            ObjectMapper objectMapper
    ) {
        this.k8sProperties = k8sProperties;
        this.objectMapper = objectMapper;
    }

    public ValidatedWorkloadConfig validate(
            CreateDeploymentRequest request,
            String defaultWorkloadBaseName
    ) {
        String deploymentName = validateDnsName(
                StringUtils.hasText(request.deploymentName()) ? request.deploymentName() : defaultWorkloadBaseName,
                "deploymentName"
        );
        String serviceName = validateDnsName(
                StringUtils.hasText(request.serviceName()) ? request.serviceName() : deploymentName,
                "serviceName"
        );

        int maxReplicas = Math.max(1, Math.min(20, k8sProperties.getMaxReplicas()));
        int replicas = request.replicaCount() != null ? request.replicaCount() : 1;
        if (replicas < 1 || replicas > maxReplicas) {
            throw new BadRequestException(
                    "Replica count must be between 1 and " + maxReplicas,
                    Map.of("replicaCount", "Allowed range: 1 to " + maxReplicas)
            );
        }

        int containerPort = request.containerPort() != null ? request.containerPort() : 8080;
        int servicePort = request.servicePort() != null ? request.servicePort() : 80;
        if (containerPort < 1 || containerPort > 65535) {
            throw new BadRequestException("Container port must be between 1 and 65535",
                    Map.of("containerPort", "Invalid TCP port"));
        }
        if (servicePort < 1 || servicePort > 65535) {
            throw new BadRequestException("Service port must be between 1 and 65535",
                    Map.of("servicePort", "Invalid TCP port"));
        }

        String cpuRequest = StringUtils.hasText(request.cpuRequest()) ? request.cpuRequest().trim() : "100m";
        String cpuLimit = StringUtils.hasText(request.cpuLimit()) ? request.cpuLimit().trim() : "500m";
        int cpuReqMillis = parseCpuMillis(cpuRequest, "cpuRequest");
        int cpuLimMillis = parseCpuMillis(cpuLimit, "cpuLimit");
        if (cpuReqMillis > cpuLimMillis) {
            throw new BadRequestException(
                    "CPU request (" + cpuRequest + ") cannot exceed CPU limit (" + cpuLimit + ")",
                    Map.of("cpuRequest", "Must be <= cpuLimit")
            );
        }

        String memoryRequest = StringUtils.hasText(request.memoryRequest()) ? request.memoryRequest().trim() : "128Mi";
        String memoryLimit = StringUtils.hasText(request.memoryLimit()) ? request.memoryLimit().trim() : "512Mi";
        long memReqMib = parseMemoryMiB(memoryRequest, "memoryRequest");
        long memLimMib = parseMemoryMiB(memoryLimit, "memoryLimit");
        if (memReqMib > memLimMib) {
            throw new BadRequestException(
                    "Memory request (" + memoryRequest + ") cannot exceed memory limit (" + memoryLimit + ")",
                    Map.of("memoryRequest", "Must be <= memoryLimit")
            );
        }

        KubernetesProbeConfig readinessProbe = validateProbe(
                request.readinessProbe() != null
                        ? request.readinessProbe()
                        : KubernetesProbeConfig.defaultReadiness(containerPort),
                containerPort,
                "readinessProbe"
        );
        KubernetesProbeConfig livenessProbe = validateProbe(
                request.livenessProbe() != null
                        ? request.livenessProbe()
                        : KubernetesProbeConfig.defaultLiveness(containerPort),
                containerPort,
                "livenessProbe"
        );
        KubernetesProbeConfig startupProbe = request.startupProbe() != null
                ? validateProbe(request.startupProbe(), containerPort, "startupProbe")
                : null;

        // Avoid restart loops: if no startup probe, liveness must not begin earlier than readiness
        if (startupProbe == null
                && livenessProbe.initialDelaySeconds() < readinessProbe.initialDelaySeconds()) {
            throw new BadRequestException(
                    "Liveness probe initialDelaySeconds must be >= readiness probe initialDelaySeconds when no startup probe is configured",
                    Map.of("livenessProbe", "Use a conservative liveness delay to avoid premature restarts")
            );
        }

        String maxSurge = validateRollingParam(
                StringUtils.hasText(request.rollingMaxSurge()) ? request.rollingMaxSurge() : "1",
                "rollingMaxSurge"
        );
        String maxUnavailable = validateRollingParam(
                StringUtils.hasText(request.rollingMaxUnavailable()) ? request.rollingMaxUnavailable() : "0",
                "rollingMaxUnavailable"
        );
        if (isZeroQuantity(maxSurge) && isZeroQuantity(maxUnavailable)) {
            throw new BadRequestException(
                    "RollingUpdate maxSurge and maxUnavailable cannot both be 0",
                    Map.of("rollingMaxSurge", "At least one of maxSurge or maxUnavailable must be > 0")
            );
        }

        int maxTimeout = Math.max(60, Math.min(1800, k8sProperties.getMaxDeploymentTimeoutSeconds()));
        int progressDeadline = request.progressDeadlineSeconds() != null ? request.progressDeadlineSeconds() : 300;
        int deployTimeout = request.deploymentTimeoutSeconds() != null ? request.deploymentTimeoutSeconds() : 300;
        if (progressDeadline < 30 || progressDeadline > maxTimeout) {
            throw new BadRequestException(
                    "progressDeadlineSeconds must be between 30 and " + maxTimeout,
                    Map.of("progressDeadlineSeconds", "Out of allowed bounds")
            );
        }
        if (deployTimeout < 30 || deployTimeout > maxTimeout) {
            throw new BadRequestException(
                    "deploymentTimeoutSeconds must be between 30 and " + maxTimeout,
                    Map.of("deploymentTimeoutSeconds", "Out of allowed bounds")
            );
        }

        boolean readOnlyRootFs = request.readOnlyRootFilesystem() == null || request.readOnlyRootFilesystem();
        boolean runAsNonRoot = request.runAsNonRoot() == null || request.runAsNonRoot();
        long runAsUser = request.runAsUser() != null ? request.runAsUser() : 10001L;
        if (runAsUser < 1000L || runAsUser > 65534L) {
            throw new BadRequestException(
                    "runAsUser must be a non-root UID between 1000 and 65534",
                    Map.of("runAsUser", "Root UID 0 and system UIDs < 1000 are prohibited")
            );
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("deploymentName", deploymentName);
        snapshot.put("serviceName", serviceName);
        snapshot.put("replicaCount", replicas);
        snapshot.put("containerPort", containerPort);
        snapshot.put("servicePort", servicePort);
        snapshot.put("cpuRequest", cpuRequest);
        snapshot.put("cpuLimit", cpuLimit);
        snapshot.put("memoryRequest", memoryRequest);
        snapshot.put("memoryLimit", memoryLimit);
        snapshot.put("readinessProbe", readinessProbe);
        snapshot.put("livenessProbe", livenessProbe);
        if (startupProbe != null) {
            snapshot.put("startupProbe", startupProbe);
        }
        snapshot.put("rollingMaxSurge", maxSurge);
        snapshot.put("rollingMaxUnavailable", maxUnavailable);
        snapshot.put("progressDeadlineSeconds", progressDeadline);
        snapshot.put("deploymentTimeoutSeconds", deployTimeout);
        snapshot.put("readOnlyRootFilesystem", readOnlyRootFs);
        snapshot.put("runAsNonRoot", runAsNonRoot);
        snapshot.put("runAsUser", runAsUser);

        String configSnapshotJson = writeJson(snapshot);
        String configChecksum = sha256Hex(configSnapshotJson);

        return new ValidatedWorkloadConfig(
                deploymentName,
                serviceName,
                replicas,
                containerPort,
                servicePort,
                cpuRequest,
                cpuLimit,
                memoryRequest,
                memoryLimit,
                readinessProbe,
                livenessProbe,
                startupProbe,
                writeJson(readinessProbe),
                writeJson(livenessProbe),
                startupProbe != null ? writeJson(startupProbe) : null,
                maxSurge,
                maxUnavailable,
                progressDeadline,
                deployTimeout,
                readOnlyRootFs,
                runAsNonRoot,
                runAsUser,
                configSnapshotJson,
                configChecksum
        );
    }

    public String validateDnsName(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            throw new BadRequestException(fieldName + " is required", Map.of(fieldName, "Must not be blank"));
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 63 || !DNS_1123_NAME.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "Invalid Kubernetes workload name \"" + raw + "\"",
                    Map.of(fieldName, "Use lowercase letters, digits, and hyphens starting with a letter (<= 63 chars)")
            );
        }
        return normalized;
    }

    public KubernetesProbeConfig validateProbe(KubernetesProbeConfig raw, int defaultPort, String fieldName) {
        if (raw == null || !StringUtils.hasText(raw.type())) {
            throw new BadRequestException("Probe type is required for " + fieldName,
                    Map.of(fieldName, "Supported probe types: HTTP_GET, TCP_SOCKET"));
        }
        String type = raw.type().trim().toUpperCase(Locale.ROOT);
        if (!"HTTP_GET".equals(type) && !"TCP_SOCKET".equals(type)) {
            throw new BadRequestException(
                    "Unsupported probe type \"" + raw.type() + "\" on " + fieldName + " (only HTTP_GET and TCP_SOCKET are permitted; EXEC probes are disabled)",
                    Map.of(fieldName, "Use HTTP_GET or TCP_SOCKET")
            );
        }
        String path = null;
        if ("HTTP_GET".equals(type)) {
            if (!StringUtils.hasText(raw.path())) {
                throw new BadRequestException("HTTP_GET probe path is required for " + fieldName,
                        Map.of(fieldName + ".path", "Provide an explicit HTTP path such as /healthz or /ready"));
            }
            path = raw.path().trim();
            if (!path.startsWith("/") || path.length() > 200 || path.contains("..")
                    || path.contains("://") || path.chars().anyMatch(c -> c <= 0x20 || c == 0x7F)) {
                throw new BadRequestException("Invalid HTTP probe path \"" + path + "\"",
                        Map.of(fieldName + ".path", "Must start with / and contain no whitespace or traversal"));
            }
        }
        int port = raw.port() != null ? raw.port() : defaultPort;
        if (port < 1 || port > 65535) {
            throw new BadRequestException("Invalid probe port on " + fieldName, Map.of(fieldName + ".port", "Invalid TCP port"));
        }
        int initialDelay = raw.initialDelaySeconds() != null ? raw.initialDelaySeconds() : 5;
        int period = raw.periodSeconds() != null ? raw.periodSeconds() : 10;
        int timeout = raw.timeoutSeconds() != null ? raw.timeoutSeconds() : 3;
        int failureThreshold = raw.failureThreshold() != null ? raw.failureThreshold() : 3;
        int successThreshold = raw.successThreshold() != null ? raw.successThreshold() : 1;

        if (initialDelay < 0 || initialDelay > 600) {
            throw new BadRequestException("initialDelaySeconds must be between 0 and 600", Map.of(fieldName, "Out of bounds"));
        }
        if (period < 2 || period > 300) {
            throw new BadRequestException("periodSeconds must be between 2 and 300", Map.of(fieldName, "Out of bounds"));
        }
        if (timeout < 1 || timeout > 60 || timeout > period) {
            throw new BadRequestException("timeoutSeconds must be between 1 and min(60, periodSeconds)", Map.of(fieldName, "Out of bounds"));
        }
        if (failureThreshold < 1 || failureThreshold > 20) {
            throw new BadRequestException("failureThreshold must be between 1 and 20", Map.of(fieldName, "Out of bounds"));
        }
        if (successThreshold < 1 || successThreshold > 10) {
            throw new BadRequestException("successThreshold must be between 1 and 10", Map.of(fieldName, "Out of bounds"));
        }
        if ("livenessProbe".equals(fieldName) && successThreshold != 1) {
            throw new BadRequestException("Kubernetes requires livenessProbe successThreshold to be 1",
                    Map.of(fieldName, "successThreshold must be 1 for liveness probes"));
        }
        return new KubernetesProbeConfig(type, path, port, initialDelay, period, timeout, failureThreshold, successThreshold);
    }

    public static int parseCpuMillis(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            throw new BadRequestException(fieldName + " is required", Map.of(fieldName, "CPU quantity required"));
        }
        String trimmed = raw.trim();
        Matcher milli = CPU_MILLI_PATTERN.matcher(trimmed);
        int millis;
        if (milli.matches()) {
            millis = Integer.parseInt(milli.group(1));
        } else {
            Matcher core = CPU_CORE_PATTERN.matcher(trimmed);
            if (!core.matches()) {
                throw new BadRequestException(
                        "Invalid Kubernetes CPU quantity \"" + raw + "\" (expected e.g. 100m, 500m, 1, or 1.5)",
                        Map.of(fieldName, "Invalid CPU quantity")
                );
            }
            millis = (int) Math.round(Double.parseDouble(core.group(1)) * 1000.0);
        }
        if (millis < MIN_CPU_MILLIS || millis > MAX_CPU_MILLIS) {
            throw new BadRequestException(
                    fieldName + " must be between 10m and 4000m (4 cores)",
                    Map.of(fieldName, "CPU quantity out of safe bounds")
            );
        }
        return millis;
    }

    public static long parseMemoryMiB(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            throw new BadRequestException(fieldName + " is required", Map.of(fieldName, "Memory quantity required"));
        }
        Matcher m = MEMORY_PATTERN.matcher(raw.trim());
        if (!m.matches()) {
            throw new BadRequestException(
                    "Invalid Kubernetes memory quantity \"" + raw + "\" (expected e.g. 128Mi, 512Mi, or 1Gi)",
                    Map.of(fieldName, "Use Mi or Gi units")
            );
        }
        long val = Long.parseLong(m.group(1));
        long mib = "Gi".equals(m.group(2)) ? val * 1024L : val;
        if (mib < MIN_MEMORY_MIB || mib > MAX_MEMORY_MIB) {
            throw new BadRequestException(
                    fieldName + " must be between 32Mi and 4096Mi (4Gi)",
                    Map.of(fieldName, "Memory quantity out of safe bounds")
            );
        }
        return mib;
    }

    private static String validateRollingParam(String raw, String fieldName) {
        String trimmed = raw.trim();
        Matcher intMatcher = SURGE_INT_PATTERN.matcher(trimmed);
        if (intMatcher.matches()) {
            int val = Integer.parseInt(intMatcher.group(1));
            if (val < 0 || val > 10) {
                throw new BadRequestException(fieldName + " integer value must be between 0 and 10",
                        Map.of(fieldName, "Out of bounds"));
            }
            return String.valueOf(val);
        }
        Matcher pctMatcher = SURGE_PCT_PATTERN.matcher(trimmed);
        if (pctMatcher.matches()) {
            int pct = Integer.parseInt(pctMatcher.group(1));
            if (pct < 0 || pct > 100) {
                throw new BadRequestException(fieldName + " percentage must be between 0% and 100%",
                        Map.of(fieldName, "Out of bounds"));
            }
            return pct + "%";
        }
        throw new BadRequestException(
                "Invalid rolling update value \"" + raw + "\" for " + fieldName + " (expected 0-10 or 0%-100%)",
                Map.of(fieldName, "Invalid rolling update quantity")
        );
    }

    private static boolean isZeroQuantity(String value) {
        return "0".equals(value) || "0%".equals(value);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Failed to serialize Kubernetes deployment configuration");
        }
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record ValidatedWorkloadConfig(
            String deploymentName,
            String serviceName,
            int replicaCount,
            int containerPort,
            int servicePort,
            String cpuRequest,
            String cpuLimit,
            String memoryRequest,
            String memoryLimit,
            KubernetesProbeConfig readinessProbe,
            KubernetesProbeConfig livenessProbe,
            KubernetesProbeConfig startupProbe,
            String readinessProbeJson,
            String livenessProbeJson,
            String startupProbeJson,
            String rollingMaxSurge,
            String rollingMaxUnavailable,
            int progressDeadlineSeconds,
            int deploymentTimeoutSeconds,
            boolean readOnlyRootFilesystem,
            boolean runAsNonRoot,
            long runAsUser,
            String configSnapshotJson,
            String configChecksumSha256
    ) {
    }
}
