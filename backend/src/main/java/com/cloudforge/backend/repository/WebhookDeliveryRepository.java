package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.WebhookDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, UUID> {

    boolean existsByDeliveryId(String deliveryId);

    Optional<WebhookDeliveryEntity> findByDeliveryId(String deliveryId);
}
