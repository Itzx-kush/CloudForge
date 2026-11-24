package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciBuildRecordEntity;
import com.cloudforge.backend.oci.entity.OciBuildStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OciBuildRecordRepository
        extends JpaRepository<OciBuildRecordEntity, UUID>, OciBuildRecordRepositoryCustom {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM OciBuildRecordEntity b WHERE b.attempt.id = :attemptId")
    Optional<OciBuildRecordEntity> findByAttemptIdForUpdate(@Param("attemptId") UUID attemptId);

    Optional<OciBuildRecordEntity> findByAttemptId(UUID attemptId);

    @Query("""
            SELECT b FROM OciBuildRecordEntity b
            LEFT JOIN FETCH b.project
            LEFT JOIN FETCH b.pipelineRun
            LEFT JOIN FETCH b.registryConnection
            WHERE b.pipelineRun.id = :runId AND b.workspace.id = :workspaceId
            ORDER BY b.createdAt DESC
            """)
    List<OciBuildRecordEntity> findAllByRunAndWorkspaceWithDetails(
            @Param("runId") UUID runId,
            @Param("workspaceId") UUID workspaceId
    );

    @Query("""
            SELECT b FROM OciBuildRecordEntity b
            LEFT JOIN FETCH b.ociHandoff
            LEFT JOIN FETCH b.registryConnection
            WHERE b.pipelineRun.id = :runId
              AND b.workspace.id = :workspaceId
              AND b.status = com.cloudforge.backend.oci.entity.OciBuildStatus.SUCCEEDED
            ORDER BY b.completedAt DESC
            """)
    List<OciBuildRecordEntity> findSucceededByRunAndWorkspace(
            @Param("runId") UUID runId,
            @Param("workspaceId") UUID workspaceId
    );

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndStatus(UUID workspaceId, OciBuildStatus status);
}
