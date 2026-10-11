package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.dto.response.DeliveryHistoryPageResponse;
import com.projects.webhookdeliveryservice.dto.response.DeliveryHistoryResponse;
import com.projects.webhookdeliveryservice.dto.response.DeliveryLogResponse;
import com.projects.webhookdeliveryservice.dto.response.RecentDeliveryResponse;
import com.projects.webhookdeliveryservice.dto.response.WebhookStatsResponse;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryLog;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.exception.ResourceNotFoundException;
import com.projects.webhookdeliveryservice.repository.DeliveryLogRepository;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryHistoryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int BODY_PREVIEW_LENGTH = 200;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final WebhookRepository webhookRepository;
    private final DeliveryStatsService deliveryStatsService;
    private final ObjectMapper objectMapper;

    public DeliveryHistoryPageResponse getWebhookDeliveries(Long webhookId, DeliveryStatus status,
                                                             int requestedPage, int requestedSize) {
        if (!webhookRepository.existsByIdAndDeletedAtIsNull(webhookId)) {
            throw new ResourceNotFoundException("Webhook not found");
        }
        int pageNumber = Math.max(0, requestedPage);
        int pageSize = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Delivery> deliveries = status == null
                ? deliveryRepository.findByWebhook_Id(webhookId, pageable)
                : deliveryRepository.findByWebhook_IdAndStatus(webhookId, status, pageable);
        Map<Long, DeliveryLogRepository.LatestDeliveryLogProjection> latestLogs = latestLogs(deliveries.getContent());
        List<DeliveryHistoryResponse> content = deliveries.getContent().stream()
                .map(delivery -> historyItem(delivery, latestLogs.get(delivery.getId())))
                .toList();
        return new DeliveryHistoryPageResponse(content, deliveries.getTotalElements(), pageNumber,
                pageSize, deliveries.getTotalPages());
    }

    public List<DeliveryLogResponse> getDeliveryLogs(Long deliveryId, boolean includeBody) {
        if (!deliveryRepository.existsById(deliveryId)) {
            throw new ResourceNotFoundException("Delivery not found");
        }
        return deliveryLogRepository.findByDelivery_IdOrderByAttemptNumberAscCreatedAtAscIdAsc(deliveryId)
                .stream().map(log -> mapLog(log, includeBody)).toList();
    }

    public WebhookStatsResponse getWebhookStats(Long webhookId, User owner) {
        webhookRepository.findByIdAndOwnerAndDeletedAtIsNull(webhookId, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook not found"));
        DeliveryStatsService.WebhookStats stats = deliveryStatsService.getWebhookStats(webhookId);
        List<Delivery> recent = deliveryRepository.findTop10ByWebhook_IdOrderByCreatedAtDescIdDesc(webhookId);
        Map<Long, DeliveryLogRepository.LatestDeliveryLogProjection> latestLogs = latestLogs(recent);
        List<RecentDeliveryResponse> recentResponses = recent.stream().map(delivery -> {
            DeliveryLogRepository.LatestDeliveryLogProjection log = latestLogs.get(delivery.getId());
            return new RecentDeliveryResponse(delivery.getId(), delivery.getEvent().getEventType(),
                    delivery.getStatus(), log == null ? null : log.getResponseTimeMs());
        }).toList();
        long outcomes = stats.successCount() + stats.failureCount();
        double successRate = outcomes == 0 ? 100 : stats.successCount() * 100.0 / outcomes;
        return new WebhookStatsResponse(webhookId, stats.successCount(), stats.failureCount(),
                successRate, stats.averageLatencyMs(), recentResponses);
    }

    private Map<Long, DeliveryLogRepository.LatestDeliveryLogProjection> latestLogs(List<Delivery> deliveries) {
        if (deliveries.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = deliveries.stream().map(Delivery::getId).toList();
        return deliveryLogRepository.findLatestLogsForDeliveries(ids).stream()
                .collect(Collectors.toMap(DeliveryLogRepository.LatestDeliveryLogProjection::getDeliveryId,
                        Function.identity()));
    }

    private DeliveryHistoryResponse historyItem(Delivery delivery,
            DeliveryLogRepository.LatestDeliveryLogProjection latestLog) {
        return new DeliveryHistoryResponse(delivery.getId(), delivery.getEvent().getId(),
                delivery.getEvent().getEventType(), delivery.getStatus(), delivery.getAttemptCount(),
                delivery.getCreatedAt(), delivery.getDeliveredAt(),
                latestLog == null ? null : latestLog.getResponseStatus(),
                latestLog == null ? null : latestLog.getErrorMessage());
    }

    private DeliveryLogResponse mapLog(DeliveryLog log, boolean includeBody) {
        JsonNode headers = parseHeaders(log.getRequestHeaders());
        String body = log.getRequestBody();
        String preview = body == null ? null
                : body.length() <= BODY_PREVIEW_LENGTH ? body : body.substring(0, BODY_PREVIEW_LENGTH) + "…";
        return new DeliveryLogResponse(log.getAttemptNumber(), log.getRequestUrl(), headers, preview,
                includeBody ? body : null, log.getResponseStatus(), log.getResponseBody(),
                log.getResponseTimeMs(), log.getErrorMessage(), log.getCreatedAt());
    }

    private JsonNode parseHeaders(String rawHeaders) {
        if (rawHeaders == null || rawHeaders.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(rawHeaders);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored delivery request headers are invalid JSON", exception);
        }
    }
}
