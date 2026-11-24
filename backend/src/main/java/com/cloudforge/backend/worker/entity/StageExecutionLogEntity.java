package com.cloudforge.backend.worker.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Bounded, ordered, sanitized log line captured from an isolated worker execution attempt.
 */
@Entity
@Table(name = "stage_execution_logs")
public class StageExecutionLogEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_run_id", nullable = false)
    private PipelineRunEntity pipelineRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_run_id", nullable = false)
    private PipelineStageRunEntity stageRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private StageExecutionAttemptEntity attempt;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "worker_id", nullable = false, length = 128)
    private String workerId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "stream_type", nullable = false, length = 16)
    private LogStreamType streamType = LogStreamType.STDOUT;

    @Column(name = "message", nullable = false, length = 2048)
    private String message;

    @Column(name = "truncated", nullable = false)
    private boolean truncated = false;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public void setWorkspace(WorkspaceEntity workspace) {
        this.workspace = workspace;
    }

    public PipelineRunEntity getPipelineRun() {
        return pipelineRun;
    }

    public void setPipelineRun(PipelineRunEntity pipelineRun) {
        this.pipelineRun = pipelineRun;
    }

    public PipelineStageRunEntity getStageRun() {
        return stageRun;
    }

    public void setStageRun(PipelineStageRunEntity stageRun) {
        this.stageRun = stageRun;
    }

    public StageExecutionAttemptEntity getAttempt() {
        return attempt;
    }

    public void setAttempt(StageExecutionAttemptEntity attempt) {
        this.attempt = attempt;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public LogStreamType getStreamType() {
        return streamType;
    }

    public void setStreamType(LogStreamType streamType) {
        this.streamType = streamType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
