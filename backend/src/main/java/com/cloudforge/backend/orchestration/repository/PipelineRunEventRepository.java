package com.cloudforge.backend.orchestration.repository;

import com.cloudforge.backend.orchestration.entity.PipelineRunEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PipelineRunEventRepository extends JpaRepository<PipelineRunEventEntity, UUID> {

    List<PipelineRunEventEntity> findAllByPipelineRunIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(
            UUID pipelineRunId,
            UUID workspaceId
    );
}
