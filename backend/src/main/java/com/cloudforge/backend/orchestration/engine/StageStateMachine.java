package com.cloudforge.backend.orchestration.engine;

import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized state-transition guard for individual pipeline stage runs.
 */
public final class StageStateMachine {

    private static final Map<PipelineStageStatus, Set<PipelineStageStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<PipelineStageStatus, Set<PipelineStageStatus>> map = new EnumMap<>(PipelineStageStatus.class);
        map.put(PipelineStageStatus.PENDING, EnumSet.of(
                PipelineStageStatus.READY,
                PipelineStageStatus.SKIPPED,
                PipelineStageStatus.CANCELLED
        ));
        map.put(PipelineStageStatus.READY, EnumSet.of(
                PipelineStageStatus.RUNNING,
                PipelineStageStatus.SKIPPED,
                PipelineStageStatus.CANCELLED
        ));
        map.put(PipelineStageStatus.RUNNING, EnumSet.of(
                PipelineStageStatus.READY,
                PipelineStageStatus.SUCCEEDED,
                PipelineStageStatus.FAILED,
                PipelineStageStatus.CANCELLED
        ));
        map.put(PipelineStageStatus.SUCCEEDED, EnumSet.noneOf(PipelineStageStatus.class));
        map.put(PipelineStageStatus.FAILED, EnumSet.noneOf(PipelineStageStatus.class));
        map.put(PipelineStageStatus.SKIPPED, EnumSet.noneOf(PipelineStageStatus.class));
        map.put(PipelineStageStatus.CANCELLED, EnumSet.noneOf(PipelineStageStatus.class));
        ALLOWED_TRANSITIONS = Map.copyOf(map);
    }

    private StageStateMachine() {
    }

    public static boolean canTransition(PipelineStageStatus from, PipelineStageStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void validateTransition(PipelineStageStatus from, PipelineStageStatus to) {
        if (!canTransition(from, to)) {
            if (from != null && from.isTerminal()) {
                throw new ConflictException(
                        "Cannot transition pipeline stage from terminal state " + from + " to " + to
                );
            }
            throw new ConflictException(
                    "Invalid pipeline stage state transition: " + from + " -> " + to
            );
        }
    }

    public static Set<PipelineStageStatus> allowedTargets(PipelineStageStatus from) {
        if (from == null) {
            return Set.of();
        }
        return Set.copyOf(ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()));
    }
}
