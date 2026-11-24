package com.cloudforge.backend.orchestration.engine;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates declarative pipeline stage graphs as strict Directed Acyclic Graphs (DAGs),
 * rejects arbitrary shell commands and secrets in stage metadata, and produces a
 * deterministic topological execution order.
 */
@Component
public class StageDagValidator {

    public static final int MAX_STAGES_PER_DEFINITION = 25;
    public static final int MAX_DEPENDENCIES_PER_STAGE = 10;
    public static final int MIN_TIMEOUT_SECONDS = 30;
    public static final int MAX_TIMEOUT_SECONDS = 7200;
    public static final int MAX_RETRIES = 5;
    public static final int MAX_METADATA_ENTRIES = 20;
    public static final int MAX_METADATA_VALUE_LENGTH = 250;

    private static final Pattern STAGE_KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");
    private static final Pattern METADATA_KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.-]{0,63}$");

    private static final Set<String> FORBIDDEN_METADATA_KEY_FRAGMENTS = Set.of(
            "COMMAND",
            "CMD",
            "SCRIPT",
            "SHELL",
            "BASH",
            "ENTRYPOINT",
            "EXEC",
            "SECRET",
            "PASSWORD",
            "PASSWD",
            "TOKEN",
            "PRIVATE_KEY",
            "API_KEY",
            "ACCESS_KEY",
            "CREDENTIAL"
    );

    public ValidatedStageGraph validateAndSort(List<StageSpecInput> rawStages) {
        if (rawStages == null || rawStages.isEmpty()) {
            throw new BadRequestException(
                    "Pipeline definition must declare at least one stage",
                    Map.of("stages", "At least one stage is required")
            );
        }
        if (rawStages.size() > MAX_STAGES_PER_DEFINITION) {
            throw new BadRequestException(
                    "Pipeline definition exceeds maximum stage limit of " + MAX_STAGES_PER_DEFINITION,
                    Map.of("stages", "At most " + MAX_STAGES_PER_DEFINITION + " stages are permitted")
            );
        }

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        Map<String, NormalizedStage> byKey = new LinkedHashMap<>();
        Map<String, Integer> declaredIndex = new HashMap<>();

        for (int i = 0; i < rawStages.size(); i++) {
            StageSpecInput raw = rawStages.get(i);
            String prefix = "stages[" + i + "]";
            if (raw == null) {
                fieldErrors.put(prefix, "Stage entry must not be null");
                continue;
            }

            String key = raw.stageKey() == null ? "" : raw.stageKey().trim().toLowerCase(Locale.ROOT);
            if (!STAGE_KEY_PATTERN.matcher(key).matches()) {
                fieldErrors.put(prefix + ".stageKey",
                        "Stage key must be 2-64 lowercase letters, digits, hyphens, or underscores starting with a letter");
                continue;
            }
            if (byKey.containsKey(key)) {
                fieldErrors.put(prefix + ".stageKey", "Duplicate stage key \"" + key + "\"");
                continue;
            }

            String displayName = raw.displayName() == null ? "" : raw.displayName().trim();
            if (displayName.length() < 2 || displayName.length() > 120) {
                fieldErrors.put(prefix + ".displayName", "Display name must be between 2 and 120 characters");
            }

            if (raw.stageType() == null) {
                fieldErrors.put(prefix + ".stageType", "Stage type is required");
            }

            int timeout = raw.timeoutSeconds() == null ? 600 : raw.timeoutSeconds();
            if (timeout < MIN_TIMEOUT_SECONDS || timeout > MAX_TIMEOUT_SECONDS) {
                fieldErrors.put(prefix + ".timeoutSeconds",
                        "Timeout must be between " + MIN_TIMEOUT_SECONDS + " and " + MAX_TIMEOUT_SECONDS + " seconds");
            }

            int retries = raw.maxRetries() == null ? 0 : raw.maxRetries();
            if (retries < 0 || retries > MAX_RETRIES) {
                fieldErrors.put(prefix + ".maxRetries",
                        "Max retries must be between 0 and " + MAX_RETRIES);
            }

            List<String> normalizedDeps = normalizeDependencies(raw.dependsOn(), key, prefix, fieldErrors);
            Map<String, String> sanitizedMetadata = validateMetadata(raw.metadata(), raw.stageType(), prefix, fieldErrors);

            byKey.put(key, new NormalizedStage(
                    key,
                    displayName,
                    raw.stageType(),
                    normalizedDeps,
                    timeout,
                    retries,
                    Boolean.TRUE.equals(raw.allowFailure()),
                    sanitizedMetadata
            ));
            declaredIndex.put(key, i);
        }

        // Validate all dependency references exist in the stage map
        for (NormalizedStage stage : byKey.values()) {
            int idx = declaredIndex.get(stage.stageKey());
            for (String dep : stage.dependsOn()) {
                if (!byKey.containsKey(dep)) {
                    fieldErrors.put("stages[" + idx + "].dependsOn",
                            "Stage \"" + stage.stageKey() + "\" depends on unknown stage \"" + dep + "\"");
                }
            }
        }

        if (!fieldErrors.isEmpty()) {
            throw new BadRequestException("Pipeline stage graph validation failed", fieldErrors);
        }

        // Deterministic Kahn's topological sort (tie-broken by declared index, then stageKey)
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String key : byKey.keySet()) {
            inDegree.put(key, 0);
            adjacency.put(key, new ArrayList<>());
        }
        for (NormalizedStage stage : byKey.values()) {
            for (String dep : stage.dependsOn()) {
                adjacency.get(dep).add(stage.stageKey());
                inDegree.put(stage.stageKey(), inDegree.get(stage.stageKey()) + 1);
            }
        }

        PriorityQueue<String> readyQueue = new PriorityQueue<>(
                Comparator.<String>comparingInt(declaredIndex::get).thenComparing(Comparator.naturalOrder())
        );
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                readyQueue.add(entry.getKey());
            }
        }

        List<ValidatedStageSpec> ordered = new ArrayList<>(byKey.size());
        int order = 0;
        while (!readyQueue.isEmpty()) {
            String currentKey = readyQueue.poll();
            NormalizedStage stage = byKey.get(currentKey);
            ordered.add(new ValidatedStageSpec(
                    stage.stageKey(),
                    stage.displayName(),
                    stage.stageType(),
                    order++,
                    stage.dependsOn(),
                    stage.timeoutSeconds(),
                    stage.maxRetries(),
                    stage.allowFailure(),
                    stage.metadata()
            ));

            for (String dependent : adjacency.get(currentKey)) {
                int remaining = inDegree.get(dependent) - 1;
                inDegree.put(dependent, remaining);
                if (remaining == 0) {
                    readyQueue.add(dependent);
                }
            }
        }

        if (ordered.size() != byKey.size()) {
            List<String> cycleKeys = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            throw new BadRequestException(
                    "Cyclic dependency detected among stages: " + String.join(", ", cycleKeys),
                    Map.of("stages", "Cyclic dependency detected involving: " + String.join(", ", cycleKeys))
            );
        }

        validateOciStageAncestry(ordered);

        return new ValidatedStageGraph(List.copyOf(ordered));
    }

    private void validateOciStageAncestry(List<ValidatedStageSpec> ordered) {
        Map<String, ValidatedStageSpec> specByKey = new HashMap<>();
        for (ValidatedStageSpec spec : ordered) {
            specByKey.put(spec.stageKey(), spec);
        }
        for (ValidatedStageSpec spec : ordered) {
            AllowedWorkerOperation op = AllowedWorkerOperation.resolveForStage(spec.stageType(), spec.metadata()).orElse(null);
            if (op == AllowedWorkerOperation.OCI_IMAGE_BUILD) {
                if (!hasUpstreamOperation(spec.stageKey(), specByKey, AllowedWorkerOperation.GITHUB_SOURCE_ACQUIRE)) {
                    throw new BadRequestException(
                            "Stage \"" + spec.stageKey() + "\" (OCI_IMAGE_BUILD) must depend directly or transitively on a SOURCE_PREP stage with GITHUB_SOURCE_ACQUIRE",
                            Map.of("stages", "OCI_IMAGE_BUILD requires an upstream GITHUB_SOURCE_ACQUIRE stage")
                    );
                }
            } else if (op == AllowedWorkerOperation.OCI_IMAGE_PUBLISH) {
                if (!hasUpstreamOperation(spec.stageKey(), specByKey, AllowedWorkerOperation.OCI_IMAGE_BUILD)) {
                    throw new BadRequestException(
                            "Stage \"" + spec.stageKey() + "\" (OCI_IMAGE_PUBLISH) must depend directly or transitively on a BUILD stage with OCI_IMAGE_BUILD",
                            Map.of("stages", "OCI_IMAGE_PUBLISH requires an upstream OCI_IMAGE_BUILD stage")
                    );
                }
            }
        }
    }

    private boolean hasUpstreamOperation(
            String stageKey,
            Map<String, ValidatedStageSpec> specByKey,
            AllowedWorkerOperation requiredOp
    ) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(specByKey.get(stageKey).dependsOn());
        while (!queue.isEmpty()) {
            String depKey = queue.remove(0);
            if (!visited.add(depKey)) {
                continue;
            }
            ValidatedStageSpec depSpec = specByKey.get(depKey);
            if (depSpec == null) {
                continue;
            }
            AllowedWorkerOperation depOp = AllowedWorkerOperation
                    .resolveForStage(depSpec.stageType(), depSpec.metadata())
                    .orElse(null);
            if (depOp == requiredOp) {
                return true;
            }
            queue.addAll(depSpec.dependsOn());
        }
        return false;
    }

    private List<String> normalizeDependencies(
            List<String> rawDeps,
            String selfKey,
            String prefix,
            Map<String, String> fieldErrors
    ) {
        if (rawDeps == null || rawDeps.isEmpty()) {
            return List.of();
        }
        if (rawDeps.size() > MAX_DEPENDENCIES_PER_STAGE) {
            fieldErrors.put(prefix + ".dependsOn",
                    "A stage may declare at most " + MAX_DEPENDENCIES_PER_STAGE + " dependencies");
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String rawDep : rawDeps) {
            if (!StringUtils.hasText(rawDep)) {
                fieldErrors.put(prefix + ".dependsOn", "Dependency stage key must not be blank");
                continue;
            }
            String normalized = rawDep.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals(selfKey)) {
                fieldErrors.put(prefix + ".dependsOn",
                        "Stage \"" + selfKey + "\" cannot depend on itself");
                continue;
            }
            unique.add(normalized);
        }
        return List.copyOf(unique);
    }

    private Map<String, String> validateMetadata(
            Map<String, String> rawMetadata,
            PipelineStageType stageType,
            String prefix,
            Map<String, String> fieldErrors
    ) {
        if (rawMetadata == null || rawMetadata.isEmpty()) {
            return Map.of();
        }
        if (rawMetadata.size() > MAX_METADATA_ENTRIES) {
            fieldErrors.put(prefix + ".metadata",
                    "Stage metadata may contain at most " + MAX_METADATA_ENTRIES + " entries");
            return Map.of();
        }

        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rawMetadata.entrySet()) {
            String rawKey = entry.getKey();
            String rawValue = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!StringUtils.hasText(rawKey)) {
                fieldErrors.put(prefix + ".metadata", "Metadata key must not be blank");
                continue;
            }
            String key = rawKey.trim();
            if (!METADATA_KEY_PATTERN.matcher(key).matches()) {
                fieldErrors.put(prefix + ".metadata." + key,
                        "Metadata key must start with a letter and contain only letters, digits, dots, underscores, or hyphens");
                continue;
            }
            String upperKey = key.toUpperCase(Locale.ROOT);
            if (FORBIDDEN_METADATA_KEY_FRAGMENTS.stream().anyMatch(upperKey::contains)) {
                fieldErrors.put(prefix + ".metadata." + key,
                        "Arbitrary commands, scripts, and secrets are prohibited in stage metadata (key \"" + key + "\")");
                continue;
            }
            if (rawValue.length() > MAX_METADATA_VALUE_LENGTH) {
                fieldErrors.put(prefix + ".metadata." + key,
                        "Metadata value must be at most " + MAX_METADATA_VALUE_LENGTH + " characters");
                continue;
            }
            if (containsForbiddenSecretValue(rawValue)) {
                fieldErrors.put(prefix + ".metadata." + key,
                        "Metadata value appears to contain a credential or private key, which is prohibited");
                continue;
            }
            if (AllowedWorkerOperation.OPERATION_METADATA_KEY.equalsIgnoreCase(key) && StringUtils.hasText(rawValue)) {
                var parsedOp = AllowedWorkerOperation.parseStrict(rawValue);
                if (parsedOp.isEmpty()) {
                    fieldErrors.put(prefix + ".metadata." + key,
                            "Unsupported operation \"" + rawValue + "\". Select an operation supported by the stage type.");
                    continue;
                }
                if (stageType != null && !parsedOp.get().supportsStageType(stageType)) {
                    fieldErrors.put(prefix + ".metadata." + key,
                            "Operation " + parsedOp.get().name() + " is not compatible with stage type " + stageType.name());
                    continue;
                }
                sanitized.put(AllowedWorkerOperation.OPERATION_METADATA_KEY, parsedOp.get().name());
                continue;
            }
            sanitized.put(key, rawValue);
        }
        return Map.copyOf(sanitized);
    }

    private boolean containsForbiddenSecretValue(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("-----BEGIN ")
                || lower.startsWith("ghp_")
                || lower.startsWith("gho_")
                || lower.startsWith("ghs_")
                || lower.startsWith("github_pat_")
                || lower.startsWith("sk_live_")
                || lower.startsWith("akia");
    }

    public record StageSpecInput(
            String stageKey,
            String displayName,
            PipelineStageType stageType,
            List<String> dependsOn,
            Integer timeoutSeconds,
            Integer maxRetries,
            Boolean allowFailure,
            Map<String, String> metadata
    ) {
    }

    public record ValidatedStageSpec(
            String stageKey,
            String displayName,
            PipelineStageType stageType,
            int executionOrder,
            List<String> dependsOn,
            int timeoutSeconds,
            int maxRetries,
            boolean allowFailure,
            Map<String, String> metadata
    ) {
    }

    public record ValidatedStageGraph(List<ValidatedStageSpec> orderedStages) {
    }

    private record NormalizedStage(
            String stageKey,
            String displayName,
            PipelineStageType stageType,
            List<String> dependsOn,
            int timeoutSeconds,
            int maxRetries,
            boolean allowFailure,
            Map<String, String> metadata
    ) {
    }
}
