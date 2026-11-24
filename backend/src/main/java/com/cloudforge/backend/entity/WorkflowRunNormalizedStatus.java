package com.cloudforge.backend.entity;

import java.util.Locale;

/**
 * Normalized CloudForge execution status derived from GitHub Actions'
 * separate {@code status} and {@code conclusion} fields.
 * Never assumes a completed run succeeded without an explicit {@code success} conclusion.
 */
public enum WorkflowRunNormalizedStatus {
    QUEUED,
    IN_PROGRESS,
    SUCCESS,
    FAILURE,
    CANCELLED,
    TIMED_OUT,
    SKIPPED,
    UNKNOWN;

    public static WorkflowRunNormalizedStatus fromGitHub(String rawStatus, String rawConclusion) {
        String status = normalize(rawStatus);
        String conclusion = normalize(rawConclusion);

        if (status == null) {
            return UNKNOWN;
        }

        return switch (status) {
            case "queued", "requested", "waiting", "pending" -> QUEUED;
            case "in_progress" -> IN_PROGRESS;
            case "completed" -> fromCompletedConclusion(conclusion);
            default -> UNKNOWN;
        };
    }

    private static WorkflowRunNormalizedStatus fromCompletedConclusion(String conclusion) {
        if (conclusion == null) {
            return UNKNOWN;
        }
        return switch (conclusion) {
            case "success" -> SUCCESS;
            case "failure", "startup_failure" -> FAILURE;
            case "cancelled" -> CANCELLED;
            case "timed_out" -> TIMED_OUT;
            case "skipped" -> SKIPPED;
            default -> UNKNOWN;
        };
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isActive() {
        return this == QUEUED || this == IN_PROGRESS;
    }

    public boolean isFailureLike() {
        return this == FAILURE || this == TIMED_OUT;
    }
}
