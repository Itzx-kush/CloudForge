package com.cloudforge.backend.worker.entity;

import com.cloudforge.backend.orchestration.entity.PipelineStageType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Strictly allowlisted operations executable inside CloudForge Phase 3.3 isolated workers.
 *
 * <p>Arbitrary shell commands, repository scripts, container builds, security scanners,
 * registry pushes, and Kubernetes deployments are intentionally excluded from this allowlist
 * until stronger microVM/sandbox isolation and Phases 3.4-3.6 controls are implemented.</p>
 */
public enum AllowedWorkerOperation {

    GITHUB_SOURCE_ACQUIRE(
            "GitHub Source Acquisition",
            EnumSet.of(PipelineStageType.SOURCE_PREP),
            true,
            "Downloads and verifies a bounded GitHub archive for the pinned commit"
    ),
    ENV_VALIDATION(
            "Environment Validation",
            EnumSet.of(PipelineStageType.SOURCE_PREP),
            true,
            "Verifies isolated container non-root identity, read-only rootfs, tmpfs bounds, and network isolation"
    ),
    CONTROLLED_SMOKE_TEST(
            "Controlled Smoke Test",
            EnumSet.of(PipelineStageType.TEST),
            true,
            "Executes a fixed, version-controlled deterministic smoke verification inside an ephemeral container"
    ),
    WORKER_DIAGNOSTICS(
            "Worker Diagnostics",
            EnumSet.of(PipelineStageType.SOURCE_PREP, PipelineStageType.TEST),
            true,
            "Checks container startup, CPU/memory/PID resource limits, timeout guards, and bounded log capture"
    );

    public static final String OPERATION_METADATA_KEY = "operation";

    private final String displayName;
    private final Set<PipelineStageType> compatibleStageTypes;
    private final boolean idempotent;
    private final String description;

    AllowedWorkerOperation(
            String displayName,
            Set<PipelineStageType> compatibleStageTypes,
            boolean idempotent,
            String description
    ) {
        this.displayName = displayName;
        this.compatibleStageTypes = Set.copyOf(compatibleStageTypes);
        this.idempotent = idempotent;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<PipelineStageType> getCompatibleStageTypes() {
        return compatibleStageTypes;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public String getDescription() {
        return description;
    }

    public boolean supportsStageType(PipelineStageType stageType) {
        return stageType != null && compatibleStageTypes.contains(stageType);
    }

    public static Optional<AllowedWorkerOperation> parseStrict(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (AllowedWorkerOperation op : values()) {
            if (op.name().equals(normalized)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }

    public static Optional<AllowedWorkerOperation> resolveForStage(
            PipelineStageType stageType,
            Map<String, String> metadata
    ) {
        if (stageType == null || metadata == null) {
            return Optional.empty();
        }
        String raw = metadata.get(OPERATION_METADATA_KEY);
        return parseStrict(raw).filter(op -> op.supportsStageType(stageType));
    }

    public static String unsupportedCapabilityMessage(PipelineStageType stageType, String rawOperation) {
        if (stageType == null) {
            return "Stage type is missing and cannot be dispatched to an isolated worker.";
        }
        if (rawOperation != null && !rawOperation.isBlank()) {
            Optional<AllowedWorkerOperation> parsed = parseStrict(rawOperation);
            if (parsed.isEmpty()) {
                return "Operation \"" + rawOperation + "\" is not in the Phase 3.3 allowlist.";
            }
            if (!parsed.get().supportsStageType(stageType)) {
                return "Operation " + parsed.get().name() + " is incompatible with stage type " + stageType.name() + ".";
            }
            return null;
        }
        return switch (stageType) {
            case SOURCE_PREP, TEST ->
                    "Declarative stage only — select an allowlisted Phase 3.3 operation (ENV_VALIDATION, CONTROLLED_SMOKE_TEST, WORKER_DIAGNOSTICS, or GITHUB_SOURCE_ACQUIRE) to enable isolated worker execution.";
            case BUILD ->
                    "Unsupported in Phase 3.3 — arbitrary repository container builds require hardened sandbox isolation and remain planned.";
            case SECURITY_SCAN ->
                    "Unsupported in Phase 3.3 — automated security scanning is planned for Phase 4 DevSecOps policy gates.";
            case CONTAINER_PACKAGE ->
                    "Unsupported in Phase 3.3 — OCI image packaging and registry publishing are planned for Phase 3.5.";
            case DEPLOYMENT ->
                    "Unsupported in Phase 3.3 — Kubernetes workload deployment is planned for Phase 3.6.";
        };
    }
}
