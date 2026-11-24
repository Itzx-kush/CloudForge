package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OciImageArtifactRepository
        extends JpaRepository<OciImageArtifactEntity, UUID>, OciImageArtifactRepositoryCustom {

    Optional<OciImageArtifactEntity> findByWorkspaceIdAndPipelineRunIdAndRegistryHostAndImageRepositoryAndManifestDigest(
            UUID workspaceId,
            UUID pipelineRunId,
            String registryHost,
            String imageRepository,
            String manifestDigest
    );

    @Query("""
            SELECT a FROM OciImageArtifactEntity a
            LEFT JOIN FETCH a.project
            LEFT JOIN FETCH a.pipelineRun
            LEFT JOIN FETCH a.buildRecord
            LEFT JOIN FETCH a.registryConnection
            WHERE a.id = :id AND a.workspace.id = :workspaceId
            """)
    Optional<OciImageArtifactEntity> findByIdAndWorkspaceIdWithDetails(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    @Query("""
            SELECT a FROM OciImageArtifactEntity a
            LEFT JOIN FETCH a.project
            LEFT JOIN FETCH a.pipelineRun
            LEFT JOIN FETCH a.registryConnection
            WHERE a.pipelineRun.id = :runId AND a.workspace.id = :workspaceId
            ORDER BY a.publishedAt DESC
            """)
    List<OciImageArtifactEntity> findAllByRunAndWorkspaceWithDetails(
            @Param("runId") UUID runId,
            @Param("workspaceId") UUID workspaceId
    );

    Optional<OciImageArtifactEntity> findFirstByAttemptId(UUID attemptId);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndVerificationStatus(UUID workspaceId, OciArtifactVerificationStatus status);
}
