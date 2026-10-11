package com.projects.webhookdeliveryservice.dto.response;

import com.projects.webhookdeliveryservice.entity.DeliveryStatus;

public record ManualRetryResponse(Long deliveryId, DeliveryStatus status, int attemptCount) {
}
