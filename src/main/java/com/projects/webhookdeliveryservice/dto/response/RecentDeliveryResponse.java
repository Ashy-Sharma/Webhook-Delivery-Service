package com.projects.webhookdeliveryservice.dto.response;

import com.projects.webhookdeliveryservice.entity.DeliveryStatus;

public record RecentDeliveryResponse(
        Long deliveryId,
        String eventType,
        DeliveryStatus status,
        Long latencyMs) {
}
