package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciHandoffStatus;
import com.cloudforge.backend.oci.entity.OciHandoffType;
import com.cloudforge.backend.oci.entity.OciSourceHandoffEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OciSourceHandoffRepository extends JpaRepository<OciSourceHandoffEntity, UUID> {

    Optional<OciSourceHandoffEntity> findBySourceAttemptIdAndHandoffType(UUID sourceAttemptId, OciHandoffType handoffType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT h FROM OciSourceHandoffEntity h
            WHERE h.pipelineRun.id = :pipelineRunId
              AND h.workspace.id = :workspaceId
              AND h.handoffType = :handoffType
              AND h.status IN :statuses
            ORDER BY h.createdAt DESC
            """)
    List<OciSourceHandoffEntity> findLatestActiveByRunForUpdate(
            @Param("pipelineRunId") UUID pipelineRunId,
            @Param("workspaceId") UUID workspaceId,
            @Param("handoffType") OciHandoffType handoffType,
            @Param("statuses") Collection<OciHandoffStatus> statuses
    );

    @Query("""
            SELECT h FROM OciSourceHandoffEntity h
            WHERE h.status IN :statuses AND h.expiresAt < :now
            ORDER BY h.expiresAt ASC
            """)
    List<OciSourceHandoffEntity> findExpiredActive(
            @Param("statuses") Collection<OciHandoffStatus> statuses,
            @Param("now") Instant now
    );

    List<OciSourceHandoffEntity> findAllByPipelineRunIdAndStatusIn(
            UUID pipelineRunId,
            Collection<OciHandoffStatus> statuses
    );

    @Query("SELECT COALESCE(SUM(h.archiveSizeBytes), 0) FROM OciSourceHandoffEntity h WHERE h.status IN :statuses")
    long sumArchiveSizeBytesByStatusIn(@Param("statuses") Collection<OciHandoffStatus> statuses);
}
