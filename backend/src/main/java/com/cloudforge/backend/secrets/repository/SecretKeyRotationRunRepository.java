package com.cloudforge.backend.secrets.repository;

import com.cloudforge.backend.secrets.entity.KeyRotationRunStatus;
import com.cloudforge.backend.secrets.entity.SecretKeyRotationRunEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SecretKeyRotationRunRepository extends JpaRepository<SecretKeyRotationRunEntity, UUID> {

    boolean existsByStatus(KeyRotationRunStatus status);

    Optional<SecretKeyRotationRunEntity> findFirstByStatusOrderByStartedAtDesc(KeyRotationRunStatus status);

    Optional<SecretKeyRotationRunEntity> findFirstByOrderByStartedAtDesc();

    List<SecretKeyRotationRunEntity> findTop20ByOrderByStartedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SecretKeyRotationRunEntity r WHERE r.id = :id")
    Optional<SecretKeyRotationRunEntity> findByIdForUpdate(@Param("id") UUID id);
}
