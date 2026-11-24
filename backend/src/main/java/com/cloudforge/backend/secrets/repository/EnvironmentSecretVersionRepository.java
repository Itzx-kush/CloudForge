package com.cloudforge.backend.secrets.repository;

import com.cloudforge.backend.secrets.entity.EnvironmentSecretVersionEntity;
import com.cloudforge.backend.secrets.entity.SecretVersionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnvironmentSecretVersionRepository extends JpaRepository<EnvironmentSecretVersionEntity, UUID> {

    @Query("""
            SELECT v FROM EnvironmentSecretVersionEntity v
            LEFT JOIN FETCH v.createdBy cb
            LEFT JOIN FETCH v.revokedBy rb
            WHERE v.secret.id = :secretId
              AND v.workspace.id = :workspaceId
            ORDER BY v.versionNumber DESC
            """)
    List<EnvironmentSecretVersionEntity> findAllBySecretIdAndWorkspaceIdOrderByVersionNumberDesc(
            @Param("secretId") UUID secretId,
            @Param("workspaceId") UUID workspaceId
    );

    Optional<EnvironmentSecretVersionEntity> findByIdAndSecretIdAndWorkspaceId(
            UUID id,
            UUID secretId,
            UUID workspaceId
    );

    Optional<EnvironmentSecretVersionEntity> findBySecretIdAndVersionNumberAndWorkspaceId(
            UUID secretId,
            int versionNumber,
            UUID workspaceId
    );

    List<EnvironmentSecretVersionEntity> findAllBySecretIdAndStatus(UUID secretId, SecretVersionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM EnvironmentSecretVersionEntity v WHERE v.id = :id")
    Optional<EnvironmentSecretVersionEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT v.id FROM EnvironmentSecretVersionEntity v
            WHERE v.kekId <> :targetKekId
            ORDER BY v.id ASC
            """)
    List<UUID> findVersionIdsNeedingKeyRewrap(
            @Param("targetKekId") String targetKekId,
            Pageable pageable
    );

    @Query("""
            SELECT v.id FROM EnvironmentSecretVersionEntity v
            WHERE v.kekId <> :targetKekId
              AND v.id > :afterId
            ORDER BY v.id ASC
            """)
    List<UUID> findVersionIdsNeedingKeyRewrapAfter(
            @Param("targetKekId") String targetKekId,
            @Param("afterId") UUID afterId,
            Pageable pageable
    );

    long countByKekIdNot(String targetKekId);

    @Query("SELECT v.kekId, COUNT(v) FROM EnvironmentSecretVersionEntity v GROUP BY v.kekId ORDER BY v.kekId ASC")
    List<Object[]> countVersionsByKekId();

    Page<EnvironmentSecretVersionEntity> findAllBySecretIdOrderByVersionNumberDesc(UUID secretId, Pageable pageable);
}
