package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.AuditEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    @Query("""
            SELECT ae FROM AuditEventEntity ae
            WHERE ae.workspace.id = :workspaceId
              AND (:action IS NULL OR ae.action = :action)
            ORDER BY ae.createdAt DESC
            """)
    Page<AuditEventEntity> findByWorkspaceId(
            @Param("workspaceId") UUID workspaceId,
            @Param("action") String action,
            Pageable pageable
    );
}
