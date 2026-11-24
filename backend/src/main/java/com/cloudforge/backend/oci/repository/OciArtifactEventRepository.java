package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciArtifactEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OciArtifactEventRepository extends JpaRepository<OciArtifactEventEntity, UUID> {

    List<OciArtifactEventEntity> findAllByArtifactIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(
            UUID artifactId,
            UUID workspaceId
    );

    List<OciArtifactEventEntity> findAllByPipelineRunIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(
            UUID pipelineRunId,
            UUID workspaceId
    );
}
