package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.DeliveryFailureReason;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryServiceTest {

    private DeliveryRepository deliveryRepository;
    private WebhookRepository webhookRepository;
    private DeliveryLogService deliveryLogService;
    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(DeliveryRepository.class);
        webhookRepository = mock(WebhookRepository.class);
        deliveryLogService = mock(DeliveryLogService.class);
        deliveryService = new DeliveryService(deliveryRepository, webhookRepository, deliveryLogService);
    }

    @Test
    void leavesClaimBudgetExhaustionForTheFencedDlqFlow() {
        when(deliveryRepository.claimForDelivery(
                eq(41L), eq(DeliveryStatus.DELIVERING), any(), any(Instant.class), eq(DeliveryStatus.RETRYING)))
                .thenReturn(0);
        Optional<DeliveryService.DeliveryAttempt> result = deliveryService.claimAndStartAttempt(41L, 8L);

        assertTrue(result.isEmpty());
        verify(deliveryRepository, org.mockito.Mockito.never()).claimExhaustedDeliveryForDlq(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void claimsExhaustedDeliveryWithoutIncrementingItsAttemptCount() {
        Instant claimedAt = Instant.parse("2026-10-08T12:00:00Z");
        Webhook webhook = Webhook.builder().id(8L).url("https://example.com/hook").build();
        Delivery delivery = Delivery.builder()
                .id(41L).status(DeliveryStatus.DELIVERING).attemptCount(5).maxAttempts(5)
                .claimedAt(claimedAt).webhook(webhook).build();
        when(deliveryRepository.claimExhaustedDeliveryForDlq(eq(41L), eq(8L),
                eq(DeliveryStatus.DELIVERING), any(), any(Instant.class))).thenReturn(1);
        when(deliveryRepository.findDetailedById(41L)).thenReturn(Optional.of(delivery));

        DeliveryService.DeliveryAttempt attempt = deliveryService
                .claimExhaustedDeliveryForDlq(41L, 8L).orElseThrow();

        assertEquals(5, attempt.attemptNumber());
        assertEquals(claimedAt, attempt.claimedAt());
        verify(deliveryRepository).claimExhaustedDeliveryForDlq(eq(41L), eq(8L),
                eq(DeliveryStatus.DELIVERING), eq(List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING)),
                any(Instant.class));
    }

    @Test
    void derivesAttemptNumberFromThePersistedDeliveryCount() {
        Instant claimedAt = Instant.parse("2026-10-08T12:00:00Z");
        Webhook webhook = Webhook.builder().id(8L).build();
        Delivery delivery = Delivery.builder()
                .id(41L)
                .status(DeliveryStatus.DELIVERING)
                .attemptCount(3)
                .claimedAt(claimedAt)
                .webhook(webhook)
                .build();
        when(deliveryRepository.claimForDelivery(
                eq(41L), eq(DeliveryStatus.DELIVERING), any(), any(Instant.class), eq(DeliveryStatus.RETRYING)))
                .thenReturn(1);
        when(deliveryRepository.findById(41L)).thenReturn(Optional.of(delivery));
        when(webhookRepository.findById(8L)).thenReturn(Optional.of(webhook));

        DeliveryService.DeliveryAttempt attempt = deliveryService.claimAndStartAttempt(41L, 8L).orElseThrow();

        assertEquals(3, attempt.attemptNumber());
        assertEquals(claimedAt, attempt.claimedAt());
    }

    @Test
    void releasesThrottledClaimUsingTheClaimTimestampFence() {
        Instant claimedAt = Instant.parse("2026-10-08T12:00:00Z");
        Instant nextRetryAt = Instant.parse("2026-10-08T12:00:02Z");
        when(deliveryRepository.releaseClaimForThrottle(
                eq(41L), eq(DeliveryStatus.DELIVERING), eq(claimedAt),
                eq(DeliveryStatus.RETRYING), eq(nextRetryAt), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        Delivery delivery = Delivery.builder().id(41L).build();
        Webhook webhook = Webhook.builder().id(8L).url("https://example.com/hook").build();
        DeliveryService.ThrottleReleaseResult released =
                deliveryService.releaseClaimForThrottle(
                        new DeliveryService.DeliveryAttempt(delivery, webhook, 1, claimedAt),
                        nextRetryAt, Instant.now(), 900_000);

        assertEquals(DeliveryService.ThrottleReleaseResult.REQUEUED, released);
        verify(deliveryRepository).releaseClaimForThrottle(
                eq(41L), eq(DeliveryStatus.DELIVERING), eq(claimedAt),
                eq(DeliveryStatus.RETRYING), eq(nextRetryAt), any(Instant.class), any(Instant.class));
    }

    @Test
    void logsWhenAnOldThrottledDeliveryIsMarkedFailed() {
        Instant claimedAt = Instant.parse("2026-10-08T12:00:00Z");
        Instant nextRetryAt = Instant.now().plusSeconds(2);
        Instant throttleDeadline = Instant.now().minusSeconds(900);
        Delivery delivery = Delivery.builder().id(41L).build();
        Webhook webhook = Webhook.builder().id(8L).url("https://example.com/hook").build();
        DeliveryService.DeliveryAttempt attempt = new DeliveryService.DeliveryAttempt(
                delivery, webhook, 1, claimedAt);
        when(deliveryRepository.releaseClaimForThrottle(
                eq(41L), eq(DeliveryStatus.DELIVERING), eq(claimedAt),
                eq(DeliveryStatus.RETRYING), eq(nextRetryAt), any(Instant.class), eq(throttleDeadline)))
                .thenReturn(0);
        when(deliveryRepository.failDeliveryAfterThrottleAge(
                eq(41L), eq(DeliveryStatus.DELIVERING), eq(claimedAt),
                eq(DeliveryStatus.FAILED), eq(DeliveryFailureReason.THROTTLE_AGE_EXCEEDED),
                any(Instant.class), eq(throttleDeadline)))
                .thenReturn(1);

        DeliveryService.ThrottleReleaseResult result = deliveryService.releaseClaimForThrottle(
                attempt, nextRetryAt, throttleDeadline, 900_000);

        assertEquals(DeliveryService.ThrottleReleaseResult.LIMIT_REACHED, result);
        verify(deliveryLogService).logThrottleAgeLimitExceeded(delivery, webhook.getUrl(), 900_000);
    }
}
