package com.projects.webhookdeliveryservice.dto.response;

import java.util.List;

public record WebhookStatsResponse(
        Long webhookId,
        long successCount,
        long failureCount,
        double successRate,
        double avgLatencyMs,
        List<RecentDeliveryResponse> recentDeliveries) {
}
