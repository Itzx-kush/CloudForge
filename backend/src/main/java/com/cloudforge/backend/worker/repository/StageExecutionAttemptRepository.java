package com.cloudforge.backend.worker.repository;

import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
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

public interface StageExecutionAttemptRepository extends JpaRepository<StageExecutionAttemptEntity, UUID> {

    interface AttemptLockIds {
        UUID getAttemptId();
        UUID getWorkspaceId();
        UUID getPipelineRunId();
        UUID getStageRunId();
        String getWorkerId();
    }

    @Query("""
            SELECT a.id AS attemptId,
                   a.workspace.id AS workspaceId,
                   a.pipelineRun.id AS pipelineRunId,
                   a.stageRun.id AS stageRunId,
                   a.workerId AS workerId
            FROM StageExecutionAttemptEntity a
            WHERE a.id = :id
            """)
    Optional<AttemptLockIds> findLockIdsById(@Param("id") UUID id);

    @Query("""
            SELECT a.id AS attemptId,
                   a.workspace.id AS workspaceId,
                   a.pipelineRun.id AS pipelineRunId,
                   a.stageRun.id AS stageRunId,
                   a.workerId AS workerId
            FROM StageExecutionAttemptEntity a
            WHERE a.status IN :statuses AND a.leaseExpiresAt < :now
            ORDER BY a.leaseExpiresAt ASC
            """)
    List<AttemptLockIds> findExpiredActiveAttemptIds(
            @Param("statuses") Collection<AttemptStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM StageExecutionAttemptEntity a WHERE a.id = :id")
    Optional<StageExecutionAttemptEntity> findByIdForUpdate(@Param("id") UUID id);

    List<StageExecutionAttemptEntity> findAllByStageRunIdAndWorkspaceIdOrderByAttemptNumberAsc(
            UUID stageRunId,
            UUID workspaceId
    );

    List<StageExecutionAttemptEntity> findAllByPipelineRunIdOrderByAttemptNumberAsc(UUID pipelineRunId);

    List<StageExecutionAttemptEntity> findAllByWorkerIdAndStatusIn(
            String workerId,
            Collection<AttemptStatus> statuses
    );

    List<StageExecutionAttemptEntity> findAllByStageRunIdAndStatusIn(
            UUID stageRunId,
            Collection<AttemptStatus> statuses
    );

    @Query("""
            SELECT a FROM StageExecutionAttemptEntity a
            WHERE a.status IN :statuses AND a.leaseExpiresAt < :now
            ORDER BY a.leaseExpiresAt ASC
            """)
    List<StageExecutionAttemptEntity> findExpiredActiveAttempts(
            @Param("statuses") Collection<AttemptStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    long countByStatusIn(Collection<AttemptStatus> statuses);

    long countByWorkspaceIdAndStatusIn(UUID workspaceId, Collection<AttemptStatus> statuses);
}
