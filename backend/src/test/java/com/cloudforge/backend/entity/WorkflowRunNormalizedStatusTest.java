package com.cloudforge.backend.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunNormalizedStatusTest {

    @Test
    void mapsQueuedAndInProgressStatusesAccurately() {
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("queued", null))
                .isEqualTo(WorkflowRunNormalizedStatus.QUEUED);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("requested", null))
                .isEqualTo(WorkflowRunNormalizedStatus.QUEUED);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("waiting", null))
                .isEqualTo(WorkflowRunNormalizedStatus.QUEUED);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("pending", null))
                .isEqualTo(WorkflowRunNormalizedStatus.QUEUED);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("in_progress", null))
                .isEqualTo(WorkflowRunNormalizedStatus.IN_PROGRESS);
    }

    @Test
    void distinguishesCompletedConclusionsAndNeverAssumesCompletedSucceeded() {
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", "success"))
                .isEqualTo(WorkflowRunNormalizedStatus.SUCCESS);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", "failure"))
                .isEqualTo(WorkflowRunNormalizedStatus.FAILURE);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", "startup_failure"))
                .isEqualTo(WorkflowRunNormalizedStatus.FAILURE);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", "cancelled"))
                .isEqualTo(WorkflowRunNormalizedStatus.CANCELLED);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", "timed_out"))
                .isEqualTo(WorkflowRunNormalizedStatus.TIMED_OUT);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", "skipped"))
                .isEqualTo(WorkflowRunNormalizedStatus.SKIPPED);

        // Critical: completed without explicit success conclusion must NEVER default to SUCCESS
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", null))
                .isEqualTo(WorkflowRunNormalizedStatus.UNKNOWN);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", ""))
                .isEqualTo(WorkflowRunNormalizedStatus.UNKNOWN);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", "neutral"))
                .isEqualTo(WorkflowRunNormalizedStatus.UNKNOWN);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("completed", "action_required"))
                .isEqualTo(WorkflowRunNormalizedStatus.UNKNOWN);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub("unexpected", "success"))
                .isEqualTo(WorkflowRunNormalizedStatus.UNKNOWN);
        assertThat(WorkflowRunNormalizedStatus.fromGitHub(null, null))
                .isEqualTo(WorkflowRunNormalizedStatus.UNKNOWN);
    }
}
