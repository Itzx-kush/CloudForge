package com.cloudforge.backend.orchestration.engine;

import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized state-transition guard for CloudForge pipeline runs.
 * Guarantees terminal states are immutable and prevents illegal transitions.
 */
public final class PipelineStateMachine {

    private static final Map<PipelineRunStatus, Set<PipelineRunStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<PipelineRunStatus, Set<PipelineRunStatus>> map = new EnumMap<>(PipelineRunStatus.class);
        map.put(PipelineRunStatus.QUEUED, EnumSet.of(PipelineRunStatus.RUNNING, PipelineRunStatus.CANCELLED));
        map.put(PipelineRunStatus.RUNNING, EnumSet.of(
                PipelineRunStatus.SUCCEEDED,
                PipelineRunStatus.FAILED,
                PipelineRunStatus.CANCELLED
        ));
        map.put(PipelineRunStatus.SUCCEEDED, EnumSet.noneOf(PipelineRunStatus.class));
        map.put(PipelineRunStatus.FAILED, EnumSet.noneOf(PipelineRunStatus.class));
        map.put(PipelineRunStatus.CANCELLED, EnumSet.noneOf(PipelineRunStatus.class));
        ALLOWED_TRANSITIONS = Map.copyOf(map);
    }

    private PipelineStateMachine() {
    }

    public static boolean canTransition(PipelineRunStatus from, PipelineRunStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void validateTransition(PipelineRunStatus from, PipelineRunStatus to) {
        if (!canTransition(from, to)) {
            if (from != null && from.isTerminal()) {
                throw new ConflictException(
                        "Cannot transition pipeline run from terminal state " + from + " to " + to
                );
            }
            throw new ConflictException(
                    "Invalid pipeline run state transition: " + from + " -> " + to
            );
        }
    }

    public static Set<PipelineRunStatus> allowedTargets(PipelineRunStatus from) {
        if (from == null) {
            return Set.of();
        }
        return Set.copyOf(ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()));
    }
}
