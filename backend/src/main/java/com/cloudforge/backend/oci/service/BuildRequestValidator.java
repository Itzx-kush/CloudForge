package com.cloudforge.backend.oci.service;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.oci.config.OciBuildProperties;
import com.cloudforge.backend.oci.entity.OciBuildMode;
import com.cloudforge.backend.oci.entity.OciRegistryConnectionEntity;
import com.cloudforge.backend.oci.entity.OciRegistryStatus;
import com.cloudforge.backend.oci.repository.OciRegistryConnectionRepository;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates declarative OCI build and publish stage configurations, rejecting path traversal,
 * absolute paths, remote URI contexts, Docker daemon endpoints, privileged modes, secret-like
 * build arguments, and unapproved registry destinations, and computes sanitized snapshots.
 */
@Service
public class BuildRequestValidator {

    public static final String META_DOCKERFILE_PATH = "dockerfilePath";
    public static final String META_BUILD_CONTEXT_PATH = "buildContextPath";
    public static final String META_TARGET_IMAGE_NAME = "targetImageName";
    public static final String META_TARGET_TAG = "targetTag";
    public static final String META_REGISTRY_CONNECTION_ID = "registryConnectionId";
    public static final String META_TARGET_PLATFORM = "targetPlatform";
    public static final String META_BUILD_MODE = "buildMode";
    public static final String META_CPU_LIMIT = "cpuLimit";
    public static final String META_MEMORY_LIMIT = "memoryLimit";
    public static final String META_BUILD_ARG_PREFIX = "buildArg.";
    public static final String META_BUILD_STAGE_KEY = "buildStageKey";

    public static final int MAX_BUILD_ARGS = 5;
    private static final Pattern SAFE_TAG_PATTERN = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$");
    private static final Pattern SAFE_BUILD_ARG_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Pattern CPU_LIMIT_PATTERN = Pattern.compile("^\\d+(?:\\.\\d{1,2})?$");
    private static final Pattern MEMORY_LIMIT_PATTERN = Pattern.compile("^\\d+[mMgG]$");
    private static final Pattern UNSAFE_PATH_CHARS = Pattern.compile("[\\\\:\\x00-\\x1f\\x7f*?\"<>|]");
    private static final Pattern URI_SCHEME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

    private static final Set<String> FORBIDDEN_ARG_FRAGMENTS = Set.of(
            "SECRET", "PASSWORD", "PASSWD", "TOKEN", "API_KEY", "ACCESS_KEY",
            "PRIVATE_KEY", "CREDENTIAL", "AUTH", "COOKIE", "SESSION", "DOCKER_HOST",
            "PRIVILEGED", "SOCKET"
    );

    private final OciBuildProperties ociProperties;
    private final OciRegistryConnectionRepository registryRepository;
    private final RegistrySecurityValidator registrySecurityValidator;
    private final ObjectMapper objectMapper;

    public BuildRequestValidator(
            OciBuildProperties ociProperties,
            OciRegistryConnectionRepository registryRepository,
            RegistrySecurityValidator registrySecurityValidator,
            ObjectMapper objectMapper
    ) {
        this.ociProperties = ociProperties;
        this.registryRepository = registryRepository;
        this.registrySecurityValidator = registrySecurityValidator;
        this.objectMapper = objectMapper;
    }

    public ValidatedOciBuildConfig validateBuildStageConfig(
            UUID workspaceId,
            String stageKey,
            Map<String, String> metadata,
            int timeoutSeconds
    ) {
        Map<String, String> meta = metadata == null ? Map.of() : metadata;

        String dockerfilePath = validateRelativePath(
                meta.getOrDefault(META_DOCKERFILE_PATH, "Dockerfile"),
                false,
                "stages." + stageKey + "." + META_DOCKERFILE_PATH
        );
        String buildContextPath = validateRelativePath(
                meta.getOrDefault(META_BUILD_CONTEXT_PATH, "."),
                true,
                "stages." + stageKey + "." + META_BUILD_CONTEXT_PATH
        );

        OciRegistryConnectionEntity registry = resolveActiveRegistryConnection(workspaceId, meta, stageKey);
        String targetImageName = registrySecurityValidator.validateRepositoryWithinNamespace(
                meta.get(META_TARGET_IMAGE_NAME),
                registry.getAllowedNamespace()
        );
        String targetTag = validateOptionalTag(meta.get(META_TARGET_TAG), stageKey);

        String targetPlatform = meta.getOrDefault(META_TARGET_PLATFORM, "linux/amd64").trim().toLowerCase(Locale.ROOT);
        Set<String> allowedPlatforms = ociProperties.parsedAllowedPlatforms();
        if (!allowedPlatforms.contains(targetPlatform)) {
            throw new BadRequestException(
                    "Unsupported target platform \"" + targetPlatform + "\"; allowed platforms: " + allowedPlatforms
            );
        }

        OciBuildMode buildMode = parseBuildMode(meta.getOrDefault(META_BUILD_MODE, OciBuildMode.ROOTLESS_BUILDKIT.name()));
        String cpuLimit = validateCpuLimit(meta.getOrDefault(META_CPU_LIMIT, "1.0"));
        String memoryLimit = validateMemoryLimit(meta.getOrDefault(META_MEMORY_LIMIT, "512m"));
        int boundedTimeout = Math.min(Math.max(timeoutSeconds, 30), ociProperties.getMaxBuildTimeoutSeconds());

        Map<String, String> buildArgs = extractAndValidateBuildArgs(meta);

        Map<String, Object> snapshotMap = new LinkedHashMap<>();
        snapshotMap.put("dockerfilePath", dockerfilePath);
        snapshotMap.put("buildContextPath", buildContextPath);
        snapshotMap.put("registryConnectionId", registry.getId().toString());
        snapshotMap.put("registryHost", registry.getRegistryHost());
        snapshotMap.put("allowedNamespace", registry.getAllowedNamespace());
        snapshotMap.put("targetImageName", targetImageName);
        if (targetTag != null) {
            snapshotMap.put("targetTag", targetTag);
        }
        snapshotMap.put("targetPlatform", targetPlatform);
        snapshotMap.put("buildMode", buildMode.name());
        snapshotMap.put("cpuLimit", cpuLimit);
        snapshotMap.put("memoryLimit", memoryLimit);
        snapshotMap.put("timeoutSeconds", boundedTimeout);
        snapshotMap.put("buildArgs", buildArgs);

        String snapshotJson = writeJson(snapshotMap);
        String configChecksum = sha256Hex(snapshotJson);

        return new ValidatedOciBuildConfig(
                registry,
                dockerfilePath,
                buildContextPath,
                targetImageName,
                targetTag,
                targetPlatform,
                buildMode,
                cpuLimit,
                memoryLimit,
                boundedTimeout,
                buildArgs,
                snapshotJson,
                configChecksum
        );
    }

    public ValidatedOciPublishConfig validatePublishStageConfig(
            UUID workspaceId,
            String stageKey,
            Map<String, String> metadata
    ) {
        Map<String, String> meta = metadata == null ? Map.of() : metadata;
        OciRegistryConnectionEntity registry = resolveActiveRegistryConnection(workspaceId, meta, stageKey);
        String targetImageName = registrySecurityValidator.validateRepositoryWithinNamespace(
                meta.get(META_TARGET_IMAGE_NAME),
                registry.getAllowedNamespace()
        );
        String targetTag = validateOptionalTag(meta.get(META_TARGET_TAG), stageKey);
        String buildStageKey = StringUtils.hasText(meta.get(META_BUILD_STAGE_KEY))
                ? meta.get(META_BUILD_STAGE_KEY).trim().toLowerCase(Locale.ROOT)
                : null;
        return new ValidatedOciPublishConfig(registry, targetImageName, targetTag, buildStageKey);
    }

    public static String validateRelativePath(String rawPath, boolean allowDotRoot, String fieldName) {
        if (!StringUtils.hasText(rawPath)) {
            throw new BadRequestException("Path must not be blank", Map.of(fieldName, "Path is required"));
        }
        String trimmed = rawPath.trim();
        if (trimmed.length() > 180) {
            throw new BadRequestException("Path exceeds maximum length of 180 characters", Map.of(fieldName, "Path too long"));
        }
        if (URI_SCHEME_PATTERN.matcher(trimmed).matches()
                || trimmed.startsWith("unix:") || trimmed.startsWith("tcp:") || trimmed.startsWith("docker:")) {
            throw new BadRequestException("Remote URI schemes and daemon endpoints are prohibited in build paths",
                    Map.of(fieldName, "Only safe repository-relative paths are permitted"));
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.startsWith("~")) {
            throw new BadRequestException("Absolute host paths are prohibited in build configuration",
                    Map.of(fieldName, "Use a safe relative path inside the verified source context"));
        }
        if (UNSAFE_PATH_CHARS.matcher(trimmed).find()) {
            throw new BadRequestException("Build path contains unsafe characters",
                    Map.of(fieldName, "Backslashes, colons, wildcards, and control characters are prohibited"));
        }
        if (allowDotRoot && ".".equals(trimmed)) {
            return ".";
        }
        String cleaned = trimmed.startsWith("./") ? trimmed.substring(2) : trimmed;
        if (cleaned.isEmpty() || cleaned.endsWith("/")) {
            throw new BadRequestException("Invalid relative build path \"" + rawPath + "\"",
                    Map.of(fieldName, "Path must not be empty or end with a slash"));
        }
        for (String segment : cleaned.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || ".git".equalsIgnoreCase(segment)) {
                throw new BadRequestException("Parent directory traversal and .git references are prohibited",
                        Map.of(fieldName, "Unsafe path segment detected"));
            }
        }
        return cleaned;
    }

    private OciRegistryConnectionEntity resolveActiveRegistryConnection(
            UUID workspaceId,
            Map<String, String> meta,
            String stageKey
    ) {
        String rawId = meta.get(META_REGISTRY_CONNECTION_ID);
        if (!StringUtils.hasText(rawId)) {
            throw new BadRequestException(
                    "Stage \"" + stageKey + "\" requires an approved workspace registryConnectionId"
            );
        }
        UUID registryId;
        try {
            registryId = UUID.fromString(rawId.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid registryConnectionId UUID on stage \"" + stageKey + "\"");
        }
        OciRegistryConnectionEntity registry = registryRepository
                .findByIdAndWorkspaceIdWithDetails(registryId, workspaceId)
                .orElseThrow(() -> new ForbiddenOperationException(
                        "Referenced OCI registry connection does not belong to this workspace"
                ));
        if (registry.getStatus() != OciRegistryStatus.ACTIVE) {
            throw new BadRequestException("Referenced OCI registry connection \"" + registry.getName() + "\" is disabled");
        }
        // Re-verify registry host/scheme against current operator security policy (fail-closed if policy tightened)
        registrySecurityValidator.validateRegistryDestination(
                registry.getRegistryHost(),
                registry.getScheme(),
                registry.getRegistryType(),
                registry.getAllowedNamespace(),
                registry.isAllowInsecureLocal()
        );
        return registry;
    }

    private static String validateOptionalTag(String rawTag, String stageKey) {
        if (!StringUtils.hasText(rawTag)) {
            return null;
        }
        String trimmed = rawTag.trim();
        if (!SAFE_TAG_PATTERN.matcher(trimmed).matches()) {
            throw new BadRequestException(
                    "Invalid OCI image tag \"" + trimmed + "\" on stage \"" + stageKey + "\""
            );
        }
        return trimmed;
    }

    private static OciBuildMode parseBuildMode(String rawMode) {
        if (!StringUtils.hasText(rawMode)) {
            return OciBuildMode.ROOTLESS_BUILDKIT;
        }
        String normalized = rawMode.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("PRIVILEGED") || normalized.contains("INSECURE") || normalized.contains("HOST")) {
            throw new BadRequestException("Privileged or insecure build modes are strictly prohibited");
        }
        try {
            return OciBuildMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(
                    "Unsupported buildMode \"" + rawMode + "\". Allowed values: ROOTLESS_BUILDKIT, LOCAL_TRUSTED_BUILDKIT"
            );
        }
    }

    private static String validateCpuLimit(String raw) {
        String trimmed = raw.trim();
        if (!CPU_LIMIT_PATTERN.matcher(trimmed).matches()) {
            throw new BadRequestException("Invalid cpuLimit \"" + raw + "\"");
        }
        double val = Double.parseDouble(trimmed);
        if (val < 0.25 || val > 2.0) {
            throw new BadRequestException("cpuLimit must be between 0.25 and 2.0");
        }
        return trimmed;
    }

    private static String validateMemoryLimit(String raw) {
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (!MEMORY_LIMIT_PATTERN.matcher(trimmed).matches()) {
            throw new BadRequestException("Invalid memoryLimit \"" + raw + "\" (expected e.g. 512m or 1g)");
        }
        long mb = trimmed.endsWith("g")
                ? Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 1024L
                : Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
        if (mb < 128 || mb > 2048) {
            throw new BadRequestException("memoryLimit must be between 128m and 2048m");
        }
        return trimmed;
    }

    private static Map<String, String> extractAndValidateBuildArgs(Map<String, String> meta) {
        Map<String, String> args = new TreeMap<>();
        for (Map.Entry<String, String> entry : meta.entrySet()) {
            if (!entry.getKey().startsWith(META_BUILD_ARG_PREFIX)) {
                continue;
            }
            if (args.size() >= MAX_BUILD_ARGS) {
                throw new BadRequestException("At most " + MAX_BUILD_ARGS + " non-secret build arguments are permitted");
            }
            String argName = entry.getKey().substring(META_BUILD_ARG_PREFIX.length()).trim();
            if (!SAFE_BUILD_ARG_KEY.matcher(argName).matches()) {
                throw new BadRequestException("Invalid build argument name \"" + argName + "\"");
            }
            String upper = argName.toUpperCase(Locale.ROOT);
            if (FORBIDDEN_ARG_FRAGMENTS.stream().anyMatch(upper::contains)) {
                throw new BadRequestException(
                        "Build argument \"" + argName + "\" appears secret-like or privileged and is prohibited"
                );
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.length() > 128 || value.contains("\n") || value.contains("\r") || value.contains("\0")) {
                throw new BadRequestException("Build argument \"" + argName + "\" value is invalid or too long");
            }
            String lowerVal = value.toLowerCase(Locale.ROOT);
            if (value.contains("-----BEGIN ") || lowerVal.startsWith("ghp_") || lowerVal.startsWith("github_pat_")
                    || lowerVal.startsWith("akia") || lowerVal.startsWith("sk_live_") || lowerVal.contains("bearer ")) {
                throw new BadRequestException("Build argument \"" + argName + "\" appears to contain a secret, which is prohibited");
            }
            args.put(argName, value);
        }
        return Map.copyOf(args);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Failed to serialize sanitized build configuration snapshot");
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

    public record ValidatedOciBuildConfig(
            OciRegistryConnectionEntity registryConnection,
            String dockerfilePath,
            String buildContextPath,
            String targetImageName,
            String targetTag,
            String targetPlatform,
            OciBuildMode buildMode,
            String cpuLimit,
            String memoryLimit,
            int timeoutSeconds,
            Map<String, String> buildArgs,
            String configSnapshotJson,
            String configChecksumSha256
    ) {
    }

    public record ValidatedOciPublishConfig(
            OciRegistryConnectionEntity registryConnection,
            String targetImageName,
            String targetTag,
            String buildStageKey
    ) {
    }
}
