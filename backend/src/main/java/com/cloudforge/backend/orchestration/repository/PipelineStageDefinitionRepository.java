package com.cloudforge.backend.orchestration.repository;

import com.cloudforge.backend.orchestration.entity.PipelineStageDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PipelineStageDefinitionRepository extends JpaRepository<PipelineStageDefinitionEntity, UUID> {

    List<PipelineStageDefinitionEntity> findAllByPipelineDefinitionIdOrderByExecutionOrderAsc(UUID pipelineDefinitionId);

    List<PipelineStageDefinitionEntity> findAllByPipelineDefinitionIdInOrderByExecutionOrderAsc(
            Collection<UUID> pipelineDefinitionIds
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PipelineStageDefinitionEntity s WHERE s.pipelineDefinition.id = :definitionId")
    void deleteAllByPipelineDefinitionId(@Param("definitionId") UUID definitionId);
}
