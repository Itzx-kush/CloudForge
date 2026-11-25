package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.WorkspaceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkspaceEntity w where w.id = :id")
    Optional<WorkspaceEntity> lockForDeploymentRequest(UUID id);

    boolean existsBySlug(String slug);

    Optional<WorkspaceEntity> findBySlug(String slug);
}
