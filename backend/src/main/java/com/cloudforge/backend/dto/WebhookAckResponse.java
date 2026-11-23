package com.cloudforge.backend.dto;

public record WebhookAckResponse(
        String status,
        String deliveryId,
        String eventType,
        String message
) {
}
