package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectEnvironmentRepository extends JpaRepository<ProjectEnvironmentEntity, UUID> {

    List<ProjectEnvironmentEntity> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);

    Optional<ProjectEnvironmentEntity> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByProjectIdAndEnvironmentType(UUID projectId, EnvironmentType environmentType);

    void deleteAllByProjectId(UUID projectId);
}
