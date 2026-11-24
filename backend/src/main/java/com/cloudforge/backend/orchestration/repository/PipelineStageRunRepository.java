package com.cloudforge.backend.orchestration.repository;

import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PipelineStageRunRepository extends JpaRepository<PipelineStageRunEntity, UUID> {

    List<PipelineStageRunEntity> findAllByPipelineRunIdOrderByExecutionOrderAsc(UUID pipelineRunId);

    List<PipelineStageRunEntity> findAllByPipelineRunIdInOrderByExecutionOrderAsc(Collection<UUID> pipelineRunIds);

    List<PipelineStageRunEntity> findAllByWorkspaceIdAndPipelineRunIdAndStatusOrderByExecutionOrderAsc(
            UUID workspaceId,
            UUID pipelineRunId,
            PipelineStageStatus status
    );
}
