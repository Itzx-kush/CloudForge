package com.cloudforge.backend.orchestration.repository;

import com.cloudforge.backend.orchestration.entity.PipelineIdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PipelineIdempotencyKeyRepository extends JpaRepository<PipelineIdempotencyKeyEntity, UUID> {

    Optional<PipelineIdempotencyKeyEntity> findByWorkspaceIdAndActorIdAndOperationAndIdempotencyKey(
            UUID workspaceId,
            UUID actorId,
            String operation,
            String idempotencyKey
    );

    /**
     * Atomically claims a scoped idempotency key without aborting the PostgreSQL transaction
     * under concurrent duplicate requests.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO pipeline_idempotency_keys (
                id,
                workspace_id,
                actor_user_id,
                operation,
                idempotency_key,
                request_fingerprint,
                pipeline_run_id,
                expires_at,
                created_at,
                updated_at
            ) VALUES (
                :id,
                :workspaceId,
                :actorUserId,
                :operation,
                :idempotencyKey,
                :requestFingerprint,
                NULL,
                :expiresAt,
                :createdAt,
                :updatedAt
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int claimKey(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId,
            @Param("actorUserId") UUID actorUserId,
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("expiresAt") Instant expiresAt,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE PipelineIdempotencyKeyEntity k
            SET k.pipelineRun.id = :runId, k.updatedAt = :updatedAt
            WHERE k.id = :id
            """)
    int attachRunId(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM PipelineIdempotencyKeyEntity k
            WHERE k.workspace.id = :workspaceId
              AND k.actor.id = :actorId
              AND k.operation = :operation
              AND k.idempotencyKey = :idempotencyKey
              AND k.expiresAt <= :now
            """)
    int deleteExpiredKey(
            @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId,
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("now") Instant now
    );
}
