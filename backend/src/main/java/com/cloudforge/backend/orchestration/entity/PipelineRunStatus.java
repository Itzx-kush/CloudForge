package com.cloudforge.backend.orchestration.entity;

/**
 * Strict lifecycle states for a CloudForge pipeline run.
 * Terminal states ({@link #SUCCEEDED}, {@link #FAILED}, {@link #CANCELLED}) are immutable.
 */
public enum PipelineRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean isCancellable() {
        return this == QUEUED || this == RUNNING;
    }
}
