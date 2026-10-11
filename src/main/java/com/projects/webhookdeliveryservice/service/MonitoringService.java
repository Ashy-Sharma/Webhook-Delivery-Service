package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.dto.response.DashboardResponse;
import com.projects.webhookdeliveryservice.dto.response.WebhookHealthResponse;
import com.projects.webhookdeliveryservice.entity.DeliveryFailureReason;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.repository.DeliveryLogRepository;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MonitoringService {

    private static final Collection<DeliveryFailureReason> DLQ_FAILURE_REASONS = List.of(
            DeliveryFailureReason.HTTP_EXHAUSTED,
            DeliveryFailureReason.WORKER_CRASH_EXHAUSTED,
            DeliveryFailureReason.THROTTLE_AGE_EXCEEDED,
            DeliveryFailureReason.INFRASTRUCTURE_DEFERRAL_EXHAUSTED);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final WebhookRepository webhookRepository;
    private final DeliveryStatsService deliveryStatsService;
    private final RateLimitService rateLimitService;

    public DashboardResponse getDashboardStats() {
        List<WebhookHealthResponse> health = getWebhookHealthList();
        long healthy = health.stream().filter(item -> item.healthScore() >= 80).count();
        long degraded = health.stream().filter(item -> item.healthScore() >= 50 && item.healthScore() < 80).count();
        long unhealthy = health.size() - healthy - degraded;

        DeliveryStatsService.DeliveryStats outcomes = deliveryStatsService.getStats();
        long outcomeCount = outcomes.successCount() + outcomes.failureCount();
        double successRate = outcomeCount == 0 ? 0 : outcomes.successCount() * 100.0 / outcomeCount;
        long crashExhausted = deliveryRepository.countByStatusAndFailureReason(
                DeliveryStatus.FAILED, DeliveryFailureReason.WORKER_CRASH_EXHAUSTED);
        long throttleExpired = deliveryRepository.countByStatusAndFailureReason(
                DeliveryStatus.FAILED, DeliveryFailureReason.THROTTLE_AGE_EXCEEDED);
        Double averageLatency = deliveryRepository.averageSuccessfulLatencySince(
                Instant.now().minus(24, ChronoUnit.HOURS));

        return new DashboardResponse(
                deliveryRepository.count(),
                outcomes.successCount(),
                outcomes.failureCount(),
                deliveryRepository.countByStatus(DeliveryStatus.RETRYING),
                successRate,
                health.size(),
                healthy,
                degraded,
                unhealthy,
                deliveryRepository.countByStatusAndFailureReasonIn(DeliveryStatus.FAILED, DLQ_FAILURE_REASONS),
                crashExhausted,
                throttleExpired,
                deliveryRepository.countByStatusAndReconciliationAttemptsGreaterThanEqual(
                        DeliveryStatus.PENDING, 5),
                averageLatency == null ? 0 : averageLatency);
    }

    public List<WebhookHealthResponse> getWebhookHealthList() {
        List<Webhook> webhooks = webhookRepository.findAllActiveNotDeleted();
        if (webhooks.isEmpty()) {
            return List.of();
        }

        List<Long> webhookIds = webhooks.stream().map(Webhook::getId).toList();
        Map<Long, Instant> lastDeliveryByWebhook = new HashMap<>();
        deliveryLogRepository.findLastDeliveryTimesForWebhooks(webhookIds)
                .forEach(row -> lastDeliveryByWebhook.put(row.getWebhookId(), row.getLastDeliveryAt()));

        return webhooks.stream()
                .map(webhook -> healthFor(webhook, lastDeliveryByWebhook.get(webhook.getId())))
                .toList();
    }

    public WebhookHealthResponse getWebhookHealth(Long webhookId) {
        Webhook webhook = webhookRepository.findById(webhookId)
                .filter(item -> item.isActive() && item.getDeletedAt() == null)
                .orElseThrow(() -> new com.projects.webhookdeliveryservice.exception.ResourceNotFoundException(
                        "Webhook not found"));
        Instant lastDeliveryAt = deliveryLogRepository.findLastDeliveryTimesForWebhooks(List.of(webhookId)).stream()
                .findFirst()
                .map(DeliveryLogRepository.WebhookLastDeliveryProjection::getLastDeliveryAt)
                .orElse(null);
        return healthFor(webhook, lastDeliveryAt);
    }

    private WebhookHealthResponse healthFor(Webhook webhook, Instant lastDeliveryAt) {
        DeliveryStatsService.WebhookStats stats = deliveryStatsService.getWebhookStats(webhook.getId());
        long outcomes = stats.successCount() + stats.failureCount();
        double successRate = outcomes == 0 ? 100 : stats.successCount() * 100.0 / outcomes;
        int score = computeHealthScore(stats.successCount(), stats.failureCount(), stats.averageLatencyMs());
        String status = score >= 80 ? "HEALTHY" : score >= 50 ? "DEGRADED" : "UNHEALTHY";
        long usage = rateLimitService.getCurrentUsage(webhook.getId());
        return new WebhookHealthResponse(webhook.getId(), webhook.getUrl(), score, status,
                stats.successCount(), stats.failureCount(), successRate, stats.averageLatencyMs(),
                lastDeliveryAt, usage + "/" + rateLimitService.getConfiguredLimit());
    }

    public int computeHealthScore(long successes, long failures, double averageLatencyMs) {
        long outcomes = successes + failures;
        if (outcomes == 0) {
            return 100;
        }
        double successRate = successes * 100.0 / outcomes;
        int latencyPenalty = averageLatencyMs > 5000 ? 10
                : averageLatencyMs > 2000 ? 5
                : averageLatencyMs > 1000 ? 2
                : 0;
        return (int) Math.max(0, Math.round(successRate - latencyPenalty));
    }
}
