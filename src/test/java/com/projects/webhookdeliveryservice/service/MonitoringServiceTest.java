package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.DeliveryFailureReason;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.repository.DeliveryLogRepository;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitoringServiceTest {

    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final DeliveryLogRepository deliveryLogRepository = mock(DeliveryLogRepository.class);
    private final WebhookRepository webhookRepository = mock(WebhookRepository.class);
    private final DeliveryStatsService deliveryStatsService = mock(DeliveryStatsService.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final MonitoringService service = new MonitoringService(deliveryRepository, deliveryLogRepository,
            webhookRepository, deliveryStatsService, rateLimitService);

    @Test
    void computesHealthScoreAndCategoriesFromTerminalOutcomesAndLatency() {
        Webhook healthy = webhook(1L);
        Webhook degraded = webhook(2L);
        Webhook unhealthy = webhook(3L);
        when(webhookRepository.findAllActiveNotDeleted()).thenReturn(List.of(healthy, degraded, unhealthy));
        when(deliveryLogRepository.findLastDeliveryTimesForWebhooks(List.of(1L, 2L, 3L))).thenReturn(List.of());
        when(deliveryStatsService.getWebhookStats(1L))
                .thenReturn(new DeliveryStatsService.WebhookStats(19, 1, 100));
        when(deliveryStatsService.getWebhookStats(2L))
                .thenReturn(new DeliveryStatsService.WebhookStats(7, 3, 2500));
        when(deliveryStatsService.getWebhookStats(3L))
                .thenReturn(new DeliveryStatsService.WebhookStats(2, 8, 500));
        when(rateLimitService.getCurrentUsage(any())).thenReturn(0L);
        when(rateLimitService.getConfiguredLimit()).thenReturn(30);

        var result = service.getWebhookHealthList();

        assertThat(result).extracting("healthStatus").containsExactly("HEALTHY", "DEGRADED", "UNHEALTHY");
        assertThat(result).extracting("healthScore").containsExactly(95, 65, 20);
        assertThat(result.get(0).rateLimitUsage()).isEqualTo("0/30");
    }

    @Test
    void dashboardSeparatesCurrentRowsFromRedisTerminalOutcomeCounters() {
        when(webhookRepository.findAllActiveNotDeleted()).thenReturn(List.of(webhook(1L)));
        when(deliveryLogRepository.findLastDeliveryTimesForWebhooks(List.of(1L))).thenReturn(List.of());
        when(deliveryStatsService.getWebhookStats(1L))
                .thenReturn(new DeliveryStatsService.WebhookStats(4, 1, 200));
        when(rateLimitService.getCurrentUsage(1L)).thenReturn(2L);
        when(rateLimitService.getConfiguredLimit()).thenReturn(30);
        when(deliveryStatsService.getStats()).thenReturn(new DeliveryStatsService.DeliveryStats(8, 3));
        when(deliveryRepository.count()).thenReturn(20L);
        when(deliveryRepository.countByStatus(DeliveryStatus.RETRYING)).thenReturn(2L);
        when(deliveryRepository.countByStatusAndFailureReasonIn(any(), any())).thenReturn(5L);
        when(deliveryRepository.countByStatusAndFailureReason(
                DeliveryStatus.FAILED, DeliveryFailureReason.WORKER_CRASH_EXHAUSTED)).thenReturn(1L);
        when(deliveryRepository.countByStatusAndFailureReason(
                DeliveryStatus.FAILED, DeliveryFailureReason.THROTTLE_AGE_EXCEEDED)).thenReturn(2L);
        when(deliveryRepository.countByStatusAndReconciliationAttemptsGreaterThanEqual(
                DeliveryStatus.PENDING, 5)).thenReturn(3L);
        when(deliveryRepository.averageSuccessfulLatencySince(any())).thenReturn(350.0);

        var dashboard = service.getDashboardStats();

        assertThat(dashboard.totalDeliveries()).isEqualTo(20);
        assertThat(dashboard.successfulDeliveries()).isEqualTo(8);
        assertThat(dashboard.failedDeliveries()).isEqualTo(3);
        assertThat(dashboard.retryingDeliveries()).isEqualTo(2);
        assertThat(dashboard.successRate()).isCloseTo(72.727, org.assertj.core.data.Offset.offset(0.001));
        assertThat(dashboard.permanentlyFailedDeliveries()).isEqualTo(5);
        assertThat(dashboard.workerCrashExhaustedDeliveries()).isEqualTo(1);
        assertThat(dashboard.throttleExpiredDeliveries()).isEqualTo(2);
        assertThat(dashboard.stuckDeliveries()).isEqualTo(3);
        assertThat(dashboard.averageLatencyMs()).isEqualTo(350);
    }

    @Test
    void noOutcomeWebhookIsHealthyAndLatencyPenaltyHasDocumentedThresholds() {
        assertThat(service.computeHealthScore(0, 0, 0)).isEqualTo(100);
        assertThat(service.computeHealthScore(9, 1, 1001)).isEqualTo(88);
        assertThat(service.computeHealthScore(9, 1, 2001)).isEqualTo(85);
        assertThat(service.computeHealthScore(9, 1, 5001)).isEqualTo(80);
        assertThat(service.computeHealthScore(0, 5, 0)).isZero();
    }

    private Webhook webhook(Long id) {
        return Webhook.builder().id(id).url("https://receiver.test/" + id).isActive(true).build();
    }
}
