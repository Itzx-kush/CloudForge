package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMemberEntity, UUID> {

    @Query("""
            SELECT wm FROM WorkspaceMemberEntity wm
            JOIN FETCH wm.workspace w
            WHERE wm.user.id = :userId
            ORDER BY w.personal DESC, w.createdAt ASC
            """)
    List<WorkspaceMemberEntity> findAllByUserIdWithWorkspace(@Param("userId") UUID userId);

    @Query("""
            SELECT wm FROM WorkspaceMemberEntity wm
            JOIN FETCH wm.user u
            WHERE wm.workspace.id = :workspaceId
            ORDER BY wm.createdAt ASC
            """)
    List<WorkspaceMemberEntity> findAllByWorkspaceIdWithUser(@Param("workspaceId") UUID workspaceId);

    @Query("""
            SELECT wm FROM WorkspaceMemberEntity wm
            JOIN FETCH wm.workspace w
            JOIN FETCH wm.user u
            WHERE wm.workspace.id = :workspaceId AND wm.user.id = :userId
            """)
    Optional<WorkspaceMemberEntity> findByWorkspaceIdAndUserId(
            @Param("workspaceId") UUID workspaceId,
            @Param("userId") UUID userId
    );

    @Query("""
            SELECT wm FROM WorkspaceMemberEntity wm
            JOIN FETCH wm.workspace w
            JOIN FETCH wm.user u
            WHERE wm.id = :memberId AND wm.workspace.id = :workspaceId
            """)
    Optional<WorkspaceMemberEntity> findByIdAndWorkspaceId(
            @Param("memberId") UUID memberId,
            @Param("workspaceId") UUID workspaceId
    );

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndRole(UUID workspaceId, WorkspaceRole role);
}
