package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.dto.DeliveryResult;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryLog;
import com.projects.webhookdeliveryservice.repository.DeliveryLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class DeliveryLogService {

    private static final int RESPONSE_BODY_MAX_LENGTH = 1024;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final DeliveryLogRepository deliveryLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public DeliveryLog logDeliveryAttempt(
            Delivery delivery,
            int attemptNumber,
            String requestUrl,
            Map<String, String> requestHeaders,
            String requestBody,
            DeliveryResult result) {

        DeliveryLog log = DeliveryLog.builder()
                .delivery(delivery)
                .attemptNumber(attemptNumber)
                .requestUrl(requestUrl)
                .requestHeaders(serializeHeaders(requestHeaders))
                .requestBody(requestBody)
                .responseStatus(result.statusCode() == 0 ? null : result.statusCode())
                .responseBody(truncate(result.responseBody(), RESPONSE_BODY_MAX_LENGTH,
                        "... [truncated]"))
                .responseTimeMs(result.responseTimeMs())
                .errorMessage(truncate(result.errorMessage(), ERROR_MESSAGE_MAX_LENGTH, "... [truncated]"))
                .build();

        return deliveryLogRepository.save(log);
    }

    private String serializeHeaders(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize delivery request headers", exception);
        }
    }

    private String truncate(String value, int maxLength, String suffix) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int prefixLength = Math.max(0, maxLength - suffix.length());
        return value.substring(0, prefixLength) + suffix;
    }
}
