package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {

    boolean existsBySlug(String slug);

    Optional<WorkspaceEntity> findBySlug(String slug);
}
