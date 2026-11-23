package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.AuditEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    Page<AuditEventEntity> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId, Pageable pageable);

    Page<AuditEventEntity> findAllByWorkspaceIdAndActionOrderByCreatedAtDesc(
            UUID workspaceId,
            String action,
            Pageable pageable
    );

    default Page<AuditEventEntity> findByWorkspaceId(UUID workspaceId, String action, Pageable pageable) {
        if (action == null || action.isBlank()) {
            return findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId, pageable);
        }
        return findAllByWorkspaceIdAndActionOrderByCreatedAtDesc(workspaceId, action.trim(), pageable);
    }
}
