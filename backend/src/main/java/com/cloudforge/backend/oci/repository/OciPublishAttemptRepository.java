package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciPublishAttemptEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OciPublishAttemptRepository extends JpaRepository<OciPublishAttemptEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM OciPublishAttemptEntity p WHERE p.attempt.id = :attemptId")
    Optional<OciPublishAttemptEntity> findByAttemptIdForUpdate(@Param("attemptId") UUID attemptId);

    Optional<OciPublishAttemptEntity> findByAttemptId(UUID attemptId);

    List<OciPublishAttemptEntity> findAllByPipelineRunIdAndWorkspaceIdOrderByStartedAtDesc(
            UUID pipelineRunId,
            UUID workspaceId
    );

    List<OciPublishAttemptEntity> findAllByArtifactIdAndWorkspaceIdOrderByStartedAtDesc(
            UUID artifactId,
            UUID workspaceId
    );
}
