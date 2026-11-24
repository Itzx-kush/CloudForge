package com.cloudforge.backend.orchestration.entity;

/**
 * Strict lifecycle states for an individual stage instance inside a pipeline run.
 */
public enum PipelineStageStatus {
    PENDING,
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }
}
