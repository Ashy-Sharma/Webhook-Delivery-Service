package com.projects.webhookdeliveryservice.dto.response;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record DeliveryLogResponse(
        int attemptNumber,
        String requestUrl,
        JsonNode requestHeaders,
        String bodyPreview,
        String requestBody,
        Integer responseStatus,
        String responseBody,
        Long responseTimeMs,
        String errorMessage,
        Instant createdAt) {
}
