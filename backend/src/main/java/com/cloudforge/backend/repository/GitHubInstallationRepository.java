package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GitHubInstallationRepository extends JpaRepository<GitHubInstallationEntity, UUID> {

    @Query("""
            SELECT gi FROM GitHubInstallationEntity gi
            JOIN FETCH gi.workspace w
            WHERE gi.workspace.id = :workspaceId
            ORDER BY gi.createdAt DESC
            """)
    List<GitHubInstallationEntity> findAllByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    @Query("""
            SELECT gi FROM GitHubInstallationEntity gi
            JOIN FETCH gi.workspace w
            WHERE gi.id = :id AND gi.workspace.id = :workspaceId
            """)
    Optional<GitHubInstallationEntity> findByIdAndWorkspaceId(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    @Query("""
            SELECT gi FROM GitHubInstallationEntity gi
            JOIN FETCH gi.workspace w
            WHERE gi.githubInstallationId = :githubInstallationId
            """)
    Optional<GitHubInstallationEntity> findByGithubInstallationId(@Param("githubInstallationId") Long githubInstallationId);

    long countByWorkspaceIdAndStatus(UUID workspaceId, InstallationStatus status);
}
