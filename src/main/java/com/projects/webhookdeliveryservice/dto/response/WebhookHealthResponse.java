package com.projects.webhookdeliveryservice.dto.response;

import java.time.Instant;

public record WebhookHealthResponse(
        Long webhookId,
        String url,
        int healthScore,
        String healthStatus,
        long successCount,
        long failureCount,
        double successRate,
        double avgLatencyMs,
        Instant lastDeliveryAt,
        String rateLimitUsage) {
}
