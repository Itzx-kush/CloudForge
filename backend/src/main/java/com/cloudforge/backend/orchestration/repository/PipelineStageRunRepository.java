package com.cloudforge.backend.orchestration.repository;

import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineStageRunRepository extends JpaRepository<PipelineStageRunEntity, UUID> {

    List<PipelineStageRunEntity> findAllByPipelineRunIdOrderByExecutionOrderAsc(UUID pipelineRunId);

    List<PipelineStageRunEntity> findAllByPipelineRunIdInOrderByExecutionOrderAsc(Collection<UUID> pipelineRunIds);

    List<PipelineStageRunEntity> findAllByWorkspaceIdAndPipelineRunIdAndStatusOrderByExecutionOrderAsc(
            UUID workspaceId,
            UUID pipelineRunId,
            PipelineStageStatus status
    );

    Optional<PipelineStageRunEntity> findByIdAndPipelineRunIdAndWorkspaceId(
            UUID id,
            UUID pipelineRunId,
            UUID workspaceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PipelineStageRunEntity s WHERE s.id = :id")
    Optional<PipelineStageRunEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Lightweight scalar projection so discovery never caches {@link PipelineStageRunEntity}
     * in the Hibernate 1st-level cache prior to acquiring {@code PESSIMISTIC_WRITE} locks.
     */
    interface EligibleStageCandidate {
        UUID getStageId();
        UUID getPipelineRunId();
        UUID getWorkspaceId();
    }

    /**
     * Bounded FIFO discovery of READY stages eligible for worker leasing:
     * active non-cancelling run, supported allowlisted operation, backoff elapsed,
     * and within retry limits.
     */
    @Query("""
            SELECT s.id AS stageId, r.id AS pipelineRunId, s.workspace.id AS workspaceId
            FROM PipelineStageRunEntity s
            JOIN s.pipelineRun r
            WHERE s.status = com.cloudforge.backend.orchestration.entity.PipelineStageStatus.READY
              AND s.allowedOperation IS NOT NULL
              AND s.allowedOperation IN :supportedOperations
              AND s.cancellationRequestedAt IS NULL
              AND (s.nextRetryAt IS NULL OR s.nextRetryAt <= :now)
              AND s.attemptNumber <= s.maxRetries
              AND r.status IN (
                  com.cloudforge.backend.orchestration.entity.PipelineRunStatus.QUEUED,
                  com.cloudforge.backend.orchestration.entity.PipelineRunStatus.RUNNING
              )
              AND r.cancellationRequestedAt IS NULL
            ORDER BY s.readyAt ASC, s.executionOrder ASC
            """)
    List<EligibleStageCandidate> findEligibleReadyStages(
            @Param("supportedOperations") Collection<String> supportedOperations,
            @Param("now") Instant now,
            Pageable pageable
    );

    long countByStatus(PipelineStageStatus status);

    long countByWorkspaceIdAndStatus(UUID workspaceId, PipelineStageStatus status);
}
