package com.cloudforge.worker.config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Configuration for the standalone CloudForge execution-plane worker agent.
 * Loaded exclusively from environment variables — never embeds credentials in code.
 */
public record WorkerAgentConfig(
        String controlPlaneBaseUrl,
        String workerId,
        String workerToken,
        String displayName,
        String hostLabel,
        String agentVersion,
        String executorImage,
        String cpuLimit,
        String memoryLimit,
        int pidsLimit,
        int maxExecutionTimeoutSeconds,
        long pollIntervalMs,
        long heartbeatIntervalMs,
        int maxConcurrency,
        List<String> supportedOperations,
        boolean secretDeliveryEnabled,
        boolean allowInsecureLocalDelivery,
        boolean ociBuildKitEnabled,
        String buildKitDriver,
        String buildKitBuilder,
        String buildKitAddr,
        boolean allowBuildNetwork,
        boolean allowLocalDevRegistry,
        Set<String> allowedBaseImages
) {
    public static final String SOURCE_OPERATION = "GITHUB_SOURCE_ACQUIRE";
    public static final String OCI_BUILD_OPERATION = "OCI_IMAGE_BUILD";
    public static final String OCI_PUBLISH_OPERATION = "OCI_IMAGE_PUBLISH";
    public static final String DEFAULT_EXECUTOR_IMAGE = "alpine:3.20";
    public static final String AGENT_VERSION = "0.3.5-phase3.5b";

    public WorkerAgentConfig(
            String controlPlaneBaseUrl,
            String workerId,
            String workerToken,
            String displayName,
            String hostLabel,
            String agentVersion,
            String executorImage,
            String cpuLimit,
            String memoryLimit,
            int pidsLimit,
            int maxExecutionTimeoutSeconds,
            long pollIntervalMs,
            long heartbeatIntervalMs,
            int maxConcurrency,
            List<String> supportedOperations
    ) {
        this(
                controlPlaneBaseUrl,
                workerId,
                workerToken,
                displayName,
                hostLabel,
                agentVersion,
                executorImage,
                cpuLimit,
                memoryLimit,
                pidsLimit,
                maxExecutionTimeoutSeconds,
                pollIntervalMs,
                heartbeatIntervalMs,
                maxConcurrency,
                supportedOperations,
                false,
                false,
                false,
                "buildx",
                "cloudforge-buildkit",
                "",
                false,
                false,
                Set.of()
        );
    }

    public WorkerAgentConfig(
            String controlPlaneBaseUrl,
            String workerId,
            String workerToken,
            String displayName,
            String hostLabel,
            String agentVersion,
            String executorImage,
            String cpuLimit,
            String memoryLimit,
            int pidsLimit,
            int maxExecutionTimeoutSeconds,
            long pollIntervalMs,
            long heartbeatIntervalMs,
            int maxConcurrency,
            List<String> supportedOperations,
            boolean secretDeliveryEnabled,
            boolean allowInsecureLocalDelivery
    ) {
        this(
                controlPlaneBaseUrl,
                workerId,
                workerToken,
                displayName,
                hostLabel,
                agentVersion,
                executorImage,
                cpuLimit,
                memoryLimit,
                pidsLimit,
                maxExecutionTimeoutSeconds,
                pollIntervalMs,
                heartbeatIntervalMs,
                maxConcurrency,
                supportedOperations,
                secretDeliveryEnabled,
                allowInsecureLocalDelivery,
                false,
                "buildx",
                "cloudforge-buildkit",
                "",
                false,
                false,
                Set.of()
        );
    }

    /**
     * Strict allowlist for trusted fixed executor images (supports pinned sha256 digests).
     * Arbitrary user-supplied Docker images are never accepted.
     */
    private static final Pattern TRUSTED_EXECUTOR_IMAGE_PATTERN = Pattern.compile(
            "^(?:alpine:(?:3\\.20(?:\\.\\d+)?|3\\.21(?:\\.\\d+)?)|busybox:1\\.36(?:\\.\\d+)?|cloudforge-executor:[A-Za-z0-9._-]+)(?:@sha256:[0-9a-f]{64})?$"
                    + "|^(?:alpine|busybox)@sha256:[0-9a-f]{64}$"
    );

    private static final Pattern MEMORY_LIMIT_PATTERN = Pattern.compile("^\\d+[mMgG]$");
    private static final Pattern CPU_LIMIT_PATTERN = Pattern.compile("^\\d+(?:\\.\\d{1,2})?$");

    public WorkerAgentConfig {
        if (controlPlaneBaseUrl == null || controlPlaneBaseUrl.isBlank()) {
            throw new IllegalArgumentException("CLOUDFORGE_CONTROL_PLANE_URL is required");
        }
        controlPlaneBaseUrl = controlPlaneBaseUrl.trim().replaceAll("/+$", "");

        if (workerId == null || !workerId.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]{2,127}$")) {
            throw new IllegalArgumentException("CLOUDFORGE_WORKER_ID must be 3-128 safe identifier characters");
        }
        if (workerToken == null || workerToken.isBlank() || workerToken.trim().length() < 16) {
            throw new IllegalArgumentException(
                    "CLOUDFORGE_WORKER_TOKEN must be provisioned via environment (>= 16 characters); refusing to start without credentials"
            );
        }
        String lowerTok = workerToken.trim().toLowerCase();
        if (lowerTok.startsWith("your_") || lowerTok.startsWith("replace_") || lowerTok.equals("changeme")) {
            throw new IllegalArgumentException("CLOUDFORGE_WORKER_TOKEN must not be a placeholder value");
        }

        if (executorImage == null || !TRUSTED_EXECUTOR_IMAGE_PATTERN.matcher(executorImage.trim()).matches()) {
            throw new IllegalArgumentException(
                    "CLOUDFORGE_EXECUTOR_IMAGE \"" + executorImage
                            + "\" is not in the trusted executor image allowlist (e.g. alpine:3.20 or pinned @sha256 digest)"
            );
        }
        executorImage = executorImage.trim();

        if (cpuLimit == null || !CPU_LIMIT_PATTERN.matcher(cpuLimit.trim()).matches()) {
            throw new IllegalArgumentException("Invalid CLOUDFORGE_EXECUTOR_CPU_LIMIT: " + cpuLimit);
        }
        double cpuVal = Double.parseDouble(cpuLimit.trim());
        if (cpuVal < 0.1 || cpuVal > 4.0) {
            throw new IllegalArgumentException("CLOUDFORGE_EXECUTOR_CPU_LIMIT must be between 0.1 and 4.0");
        }

        if (memoryLimit == null || !MEMORY_LIMIT_PATTERN.matcher(memoryLimit.trim()).matches()) {
            throw new IllegalArgumentException("Invalid CLOUDFORGE_EXECUTOR_MEMORY_LIMIT (expected e.g. 128m): " + memoryLimit);
        }
        if (pidsLimit < 16 || pidsLimit > 256) {
            throw new IllegalArgumentException("CLOUDFORGE_EXECUTOR_PIDS_LIMIT must be between 16 and 256");
        }
        if (maxExecutionTimeoutSeconds < 10 || maxExecutionTimeoutSeconds > 1800) {
            throw new IllegalArgumentException("maxExecutionTimeoutSeconds must be between 10 and 1800");
        }
        if (maxConcurrency < 1 || maxConcurrency > 4) {
            throw new IllegalArgumentException("CLOUDFORGE_MAX_CONCURRENCY must be between 1 and 4");
        }
        if (secretDeliveryEnabled && !isHttpsOrAllowedLoopback(controlPlaneBaseUrl, allowInsecureLocalDelivery)) {
            throw new IllegalArgumentException(
                    "CLOUDFORGE_CONTROL_PLANE_URL must use https:// when worker secret delivery is enabled "
                            + "(or set CLOUDFORGE_WORKER_ALLOW_INSECURE_LOCAL_DELIVERY=true for loopback development only)"
            );
        }
        supportedOperations = List.copyOf(supportedOperations);
        allowedBaseImages = allowedBaseImages == null ? Set.of() : Set.copyOf(allowedBaseImages);
        buildKitDriver = (buildKitDriver == null || buildKitDriver.isBlank()) ? "buildx" : buildKitDriver.trim().toLowerCase(Locale.ROOT);
        buildKitBuilder = buildKitBuilder == null ? "" : buildKitBuilder.trim();
        buildKitAddr = buildKitAddr == null ? "" : buildKitAddr.trim();

        if (supportedOperations.contains(SOURCE_OPERATION)) {
            URI endpoint = URI.create(controlPlaneBaseUrl);
            if (ociBuildKitEnabled
                    || !supportedOperations.equals(List.of(SOURCE_OPERATION))
                    || maxConcurrency != 1
                    || !"https".equals(endpoint.getScheme())
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("Source workers require a dedicated HTTPS control plane connection");
            }
        }
        boolean hasOciOp = supportedOperations.contains(OCI_BUILD_OPERATION)
                || supportedOperations.contains(OCI_PUBLISH_OPERATION);
        if (ociBuildKitEnabled || hasOciOp) {
            if (!ociBuildKitEnabled || !hasOciOp
                    || !supportedOperations.stream().allMatch(op -> op.equals(OCI_BUILD_OPERATION) || op.equals(OCI_PUBLISH_OPERATION))
                    || maxConcurrency > 2) {
                throw new IllegalArgumentException(
                        "OCI BuildKit workers must enable CLOUDFORGE_WORKER_OCI_BUILDKIT_ENABLED=true, advertise only OCI operations, and keep maxConcurrency <= 2"
                );
            }
            if (!"buildx".equals(buildKitDriver) && !"buildctl".equals(buildKitDriver)) {
                throw new IllegalArgumentException("Unsupported CLOUDFORGE_OCI_BUILDKIT_DRIVER: " + buildKitDriver);
            }
            if (!isHttpsOrAllowedLoopback(controlPlaneBaseUrl, allowInsecureLocalDelivery)) {
                throw new IllegalArgumentException(
                        "OCI BuildKit workers require https:// control plane URL (or loopback with CLOUDFORGE_WORKER_ALLOW_INSECURE_LOCAL_DELIVERY=true)"
                );
            }
        }
    }

    public boolean sourceOnly() {
        return supportedOperations.equals(List.of(SOURCE_OPERATION));
    }

    private static boolean isHttpsOrAllowedLoopback(String baseUrl, boolean allowInsecureLocal) {
        String lower = baseUrl.toLowerCase();
        if (lower.startsWith("https://")) {
            return true;
        }
        if (!allowInsecureLocal) {
            return false;
        }
        return lower.startsWith("http://127.0.0.1")
                || lower.startsWith("http://localhost")
                || lower.startsWith("http://[::1]");
    }

    public static WorkerAgentConfig fromEnv(Map<String, String> env) {
        String baseUrl = env.getOrDefault("CLOUDFORGE_CONTROL_PLANE_URL", "http://127.0.0.1:8080");
        String workerId = env.getOrDefault("CLOUDFORGE_WORKER_ID", "local-docker-worker-1");
        String token = env.getOrDefault("CLOUDFORGE_WORKER_TOKEN", "");
        String displayName = env.getOrDefault("CLOUDFORGE_WORKER_DISPLAY_NAME", "CloudForge Local Docker Worker");
        String hostLabel = env.getOrDefault("CLOUDFORGE_WORKER_HOST_LABEL", "local-dev-host");
        String image = env.getOrDefault("CLOUDFORGE_EXECUTOR_IMAGE", DEFAULT_EXECUTOR_IMAGE);
        String cpu = env.getOrDefault("CLOUDFORGE_EXECUTOR_CPU_LIMIT", "0.5");
        String mem = env.getOrDefault("CLOUDFORGE_EXECUTOR_MEMORY_LIMIT", "128m");
        int pids = parseInt(env.get("CLOUDFORGE_EXECUTOR_PIDS_LIMIT"), 64);
        int maxTimeout = parseInt(env.get("CLOUDFORGE_EXECUTOR_MAX_TIMEOUT_SECONDS"), 300);
        long pollMs = parseLong(env.get("CLOUDFORGE_POLL_INTERVAL_MS"), 2000L);
        long hbMs = parseLong(env.get("CLOUDFORGE_HEARTBEAT_INTERVAL_MS"), 5000L);
        int concurrency = parseInt(env.get("CLOUDFORGE_MAX_CONCURRENCY"), 1);
        boolean secretDeliveryEnabled = Boolean.parseBoolean(
                env.getOrDefault("CLOUDFORGE_WORKER_SECRET_DELIVERY_ENABLED", "false")
        );
        boolean allowInsecureLocalDelivery = Boolean.parseBoolean(
                env.getOrDefault("CLOUDFORGE_WORKER_ALLOW_INSECURE_LOCAL_DELIVERY", "false")
        );
        boolean sourceOnly = Boolean.parseBoolean(env.getOrDefault("CLOUDFORGE_WORKER_SOURCE_ONLY", "false"));
        boolean ociBuildKit = Boolean.parseBoolean(env.getOrDefault("CLOUDFORGE_WORKER_OCI_BUILDKIT_ENABLED", "false"));
        if (sourceOnly && ociBuildKit) {
            throw new IllegalArgumentException(
                    "CLOUDFORGE_WORKER_SOURCE_ONLY and CLOUDFORGE_WORKER_OCI_BUILDKIT_ENABLED are mutually exclusive"
            );
        }

        String buildKitDriver = env.getOrDefault("CLOUDFORGE_OCI_BUILDKIT_DRIVER", "buildx");
        String buildKitBuilder = env.getOrDefault("CLOUDFORGE_OCI_BUILDKIT_BUILDER", "");
        String buildKitAddr = env.getOrDefault("CLOUDFORGE_OCI_BUILDKIT_ADDR", "");
        boolean allowBuildNetwork = Boolean.parseBoolean(env.getOrDefault("CLOUDFORGE_OCI_ALLOW_BUILD_NETWORK", "false"));
        boolean allowLocalDevRegistry = Boolean.parseBoolean(
                env.getOrDefault("CLOUDFORGE_OCI_ALLOW_LOCAL_DEV_REGISTRY_WORKER", "false")
        );
        Set<String> allowedBaseImages = parseCsvSet(env.getOrDefault("CLOUDFORGE_OCI_ALLOWED_BASE_IMAGES", ""));

        List<String> ops = sourceOnly
                ? List.of(SOURCE_OPERATION)
                : ociBuildKit
                ? List.of(OCI_BUILD_OPERATION, OCI_PUBLISH_OPERATION)
                : List.of("ENV_VALIDATION", "CONTROLLED_SMOKE_TEST", "WORKER_DIAGNOSTICS");

        return new WorkerAgentConfig(
                baseUrl,
                workerId,
                token,
                displayName,
                hostLabel,
                AGENT_VERSION,
                image,
                cpu,
                mem,
                pids,
                maxTimeout,
                pollMs,
                hbMs,
                concurrency,
                ops,
                secretDeliveryEnabled,
                allowInsecureLocalDelivery,
                ociBuildKit,
                buildKitDriver,
                buildKitBuilder,
                buildKitAddr,
                allowBuildNetwork,
                allowLocalDevRegistry,
                allowedBaseImages
        );
    }

    private static Set<String> parseCsvSet(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(raw.trim());
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Long.parseLong(raw.trim());
    }
}
