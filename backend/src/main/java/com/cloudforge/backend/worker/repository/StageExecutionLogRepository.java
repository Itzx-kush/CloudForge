package com.cloudforge.backend.worker.repository;

import com.cloudforge.backend.worker.entity.StageExecutionLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StageExecutionLogRepository extends JpaRepository<StageExecutionLogEntity, UUID> {

    Optional<StageExecutionLogEntity> findTopByAttemptIdOrderBySequenceNumberDesc(UUID attemptId);

    Page<StageExecutionLogEntity> findAllByStageRunIdAndWorkspaceIdOrderByAttemptNumberAscSequenceNumberAsc(
            UUID stageRunId,
            UUID workspaceId,
            Pageable pageable
    );

    Page<StageExecutionLogEntity> findAllByStageRunIdAndWorkspaceIdAndAttemptNumberOrderBySequenceNumberAsc(
            UUID stageRunId,
            UUID workspaceId,
            int attemptNumber,
            Pageable pageable
    );

    @Query("SELECT COALESCE(MAX(l.sequenceNumber), 0) FROM StageExecutionLogEntity l WHERE l.attempt.id = :attemptId")
    int findMaxSequenceNumberByAttemptId(@Param("attemptId") UUID attemptId);

    long countByAttemptId(UUID attemptId);

    @Query("SELECT COALESCE(SUM(LENGTH(l.message)), 0) FROM StageExecutionLogEntity l WHERE l.attempt.id = :attemptId")
    long sumMessageCharsByAttemptId(@Param("attemptId") UUID attemptId);
}
