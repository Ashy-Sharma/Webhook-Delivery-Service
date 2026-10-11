package com.projects.webhookdeliveryservice.dto.response;

import com.projects.webhookdeliveryservice.entity.DeliveryStatus;

import java.time.Instant;

public record DeliveryHistoryResponse(
        Long deliveryId,
        Long eventId,
        String eventType,
        DeliveryStatus status,
        int attemptCount,
        Instant createdAt,
        Instant deliveredAt,
        Integer lastAttemptStatus,
        String lastAttemptError) {
}
