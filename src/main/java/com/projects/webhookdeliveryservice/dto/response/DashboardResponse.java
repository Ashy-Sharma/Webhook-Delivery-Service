package com.projects.webhookdeliveryservice.dto.response;

public record DashboardResponse(
        long totalDeliveries,
        long successfulDeliveries,
        long failedDeliveries,
        long retryingDeliveries,
        double successRate,
        long activeWebhooks,
        long healthyWebhooks,
        long degradedWebhooks,
        long unhealthyWebhooks,
        long permanentlyFailedDeliveries,
        long workerCrashExhaustedDeliveries,
        long throttleExpiredDeliveries,
        long stuckDeliveries,
        double averageLatencyMs) {
}
