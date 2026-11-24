package com.cloudforge.backend.secrets.repository;

import com.cloudforge.backend.secrets.entity.PipelineRunSecretReferenceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PipelineRunSecretReferenceRepository extends JpaRepository<PipelineRunSecretReferenceEntity, UUID> {

    @Query("""
            SELECT r FROM PipelineRunSecretReferenceEntity r
            JOIN FETCH r.secret s
            JOIN FETCH r.secretVersion sv
            JOIN FETCH r.environment env
            WHERE r.stageRun.id = :stageRunId
            ORDER BY r.deliveryAlias ASC
            """)
    List<PipelineRunSecretReferenceEntity> findAllByStageRunIdWithSecretDetails(@Param("stageRunId") UUID stageRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM PipelineRunSecretReferenceEntity r
            JOIN FETCH r.secret s
            JOIN FETCH r.secretVersion sv
            JOIN FETCH r.environment env
            WHERE r.stageRun.id = :stageRunId
            ORDER BY r.deliveryAlias ASC
            """)
    List<PipelineRunSecretReferenceEntity> findAllByStageRunIdWithSecretDetailsForUpdate(@Param("stageRunId") UUID stageRunId);

    List<PipelineRunSecretReferenceEntity> findAllByPipelineRunIdOrderByCreatedAtAsc(UUID pipelineRunId);

    boolean existsByStageRunId(UUID stageRunId);

    boolean existsBySecretId(UUID secretId);

    long countByStageRunId(UUID stageRunId);
}
