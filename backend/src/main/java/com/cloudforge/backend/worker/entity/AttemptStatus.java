package com.cloudforge.backend.worker.entity;

public enum AttemptStatus {
    CLAIMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    INTERRUPTED,
    EXPIRED;

    public boolean isActive() {
        return this == CLAIMED || this == RUNNING;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
