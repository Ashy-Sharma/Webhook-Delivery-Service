package com.projects.webhookdeliveryservice.dto;

public record DeliveryResult(
        int statusCode,
        String responseBody,
        long responseTimeMs,
        String errorMessage,
        boolean success) {
}
