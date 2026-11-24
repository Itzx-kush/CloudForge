package com.cloudforge.backend.orchestration.engine;

import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure deterministic DAG dependency evaluator for CloudForge pipeline stage runs.
 *
 * <h3>Dependency &amp; Failure Propagation Rules</h3>
 * <ul>
 *   <li>A {@code PENDING} stage becomes {@code READY} when every declared predecessor is
 *       {@code SUCCEEDED} (or {@code FAILED} with {@code allowFailure = true}).</li>
 *   <li>If any required predecessor is {@code FAILED} (with {@code allowFailure = false}),
 *       {@code SKIPPED}, or {@code CANCELLED}, the downstream {@code PENDING}/{@code READY}
 *       stage transitions to {@code SKIPPED} so failed paths never silently execute.</li>
 *   <li>Evaluation iterates until fixed point so transitive downstream stages are skipped
 *       deterministically in a single pass.</li>
 * </ul>
 */
public final class StageDependencyEvaluator {

    private StageDependencyEvaluator() {
    }

    public static List<StageTransitionDecision> evaluateProgressions(List<StageNodeState> stages) {
        Map<String, MutableStageState> byKey = new LinkedHashMap<>();
        for (StageNodeState stage : stages) {
            byKey.put(stage.stageKey(), new MutableStageState(stage));
        }

        List<StageTransitionDecision> decisions = new ArrayList<>();
        boolean changed;
        do {
            changed = false;
            for (MutableStageState node : byKey.values()) {
                if (node.status != PipelineStageStatus.PENDING && node.status != PipelineStageStatus.READY) {
                    continue;
                }

                String blockingPredecessor = findBlockingPredecessor(node.dependsOn, byKey);
                if (blockingPredecessor != null) {
                    PipelineStageStatus previous = node.status;
                    PipelineStageStatus blockingStatus = byKey.get(blockingPredecessor).status;
                    StageStateMachine.validateTransition(previous, PipelineStageStatus.SKIPPED);
                    node.status = PipelineStageStatus.SKIPPED;
                    decisions.add(new StageTransitionDecision(
                            node.stageKey,
                            previous,
                            PipelineStageStatus.SKIPPED,
                            "Skipped because upstream dependency \"" + blockingPredecessor + "\" ended with status " + blockingStatus
                    ));
                    changed = true;
                    continue;
                }

                if (node.status == PipelineStageStatus.PENDING && areAllDependenciesSatisfied(node.dependsOn, byKey)) {
                    StageStateMachine.validateTransition(PipelineStageStatus.PENDING, PipelineStageStatus.READY);
                    node.status = PipelineStageStatus.READY;
                    String reason = node.dependsOn.isEmpty()
                            ? "Root stage ready — awaiting isolated worker assignment (Phase 3.3)"
                            : "All upstream dependencies succeeded — ready for worker assignment";
                    decisions.add(new StageTransitionDecision(
                            node.stageKey,
                            PipelineStageStatus.PENDING,
                            PipelineStageStatus.READY,
                            reason
                    ));
                    changed = true;
                }
            }
        } while (changed);

        return List.copyOf(decisions);
    }

    /**
     * Evaluates whether all stage runs have reached terminal states and derives
     * the corresponding pipeline run terminal outcome (used by future Phase 3.3 trusted workers).
     */
    public static Optional<PipelineRunStatus> evaluateTerminalRunOutcome(List<StageNodeState> stages) {
        if (stages == null || stages.isEmpty()) {
            return Optional.empty();
        }
        boolean hasRequiredFailure = false;
        boolean hasCancelled = false;
        for (StageNodeState stage : stages) {
            if (!stage.status().isTerminal()) {
                return Optional.empty();
            }
            if (stage.status() == PipelineStageStatus.FAILED && !stage.allowFailure()) {
                hasRequiredFailure = true;
            }
            if (stage.status() == PipelineStageStatus.CANCELLED) {
                hasCancelled = true;
            }
        }
        if (hasCancelled) {
            return Optional.of(PipelineRunStatus.CANCELLED);
        }
        if (hasRequiredFailure) {
            return Optional.of(PipelineRunStatus.FAILED);
        }
        return Optional.of(PipelineRunStatus.SUCCEEDED);
    }

    private static String findBlockingPredecessor(
            List<String> dependsOn,
            Map<String, MutableStageState> byKey
    ) {
        for (String depKey : dependsOn) {
            MutableStageState dep = byKey.get(depKey);
            if (dep == null) {
                return depKey;
            }
            if ((dep.status == PipelineStageStatus.FAILED && !dep.allowFailure)
                    || dep.status == PipelineStageStatus.SKIPPED
                    || dep.status == PipelineStageStatus.CANCELLED) {
                return depKey;
            }
        }
        return null;
    }

    private static boolean areAllDependenciesSatisfied(
            List<String> dependsOn,
            Map<String, MutableStageState> byKey
    ) {
        for (String depKey : dependsOn) {
            MutableStageState dep = byKey.get(depKey);
            if (dep == null) {
                return false;
            }
            boolean satisfied = dep.status == PipelineStageStatus.SUCCEEDED
                    || (dep.status == PipelineStageStatus.FAILED && dep.allowFailure);
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    public record StageNodeState(
            String stageKey,
            PipelineStageStatus status,
            List<String> dependsOn,
            boolean allowFailure
    ) {
    }

    public record StageTransitionDecision(
            String stageKey,
            PipelineStageStatus fromStatus,
            PipelineStageStatus toStatus,
            String reason
    ) {
    }

    private static final class MutableStageState {
        final String stageKey;
        final List<String> dependsOn;
        final boolean allowFailure;
        PipelineStageStatus status;

        MutableStageState(StageNodeState source) {
            this.stageKey = source.stageKey();
            this.dependsOn = source.dependsOn() == null ? List.of() : source.dependsOn();
            this.allowFailure = source.allowFailure();
            this.status = source.status();
        }
    }
}
