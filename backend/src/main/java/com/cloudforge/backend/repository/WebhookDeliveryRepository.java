package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.WebhookDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, UUID> {

    boolean existsByDeliveryId(String deliveryId);

    Optional<WebhookDeliveryEntity> findByDeliveryId(String deliveryId);

    /**
     * Atomically claims a GitHub webhook delivery_id before running side effects.
     * Returns 1 when claimed by this caller or 0 when already delivered, avoiding
     * PostgreSQL transaction-abort errors on concurrent duplicate deliveries.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO webhook_deliveries (
                id,
                delivery_id,
                event_type,
                action,
                github_installation_id,
                payload_sha256,
                status,
                summary,
                received_at,
                created_at,
                updated_at
            ) VALUES (
                :id,
                :deliveryId,
                :eventType,
                :action,
                :githubInstallationId,
                :payloadSha256,
                :status,
                :summary,
                :receivedAt,
                :createdAt,
                :updatedAt
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int claimDelivery(
            @Param("id") UUID id,
            @Param("deliveryId") String deliveryId,
            @Param("eventType") String eventType,
            @Param("action") String action,
            @Param("githubInstallationId") Long githubInstallationId,
            @Param("payloadSha256") String payloadSha256,
            @Param("status") String status,
            @Param("summary") String summary,
            @Param("receivedAt") Instant receivedAt,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WebhookDeliveryEntity d
            SET d.summary = :summary, d.updatedAt = :updatedAt
            WHERE d.deliveryId = :deliveryId
            """)
    int updateDeliverySummary(
            @Param("deliveryId") String deliveryId,
            @Param("summary") String summary,
            @Param("updatedAt") Instant updatedAt
    );
}
