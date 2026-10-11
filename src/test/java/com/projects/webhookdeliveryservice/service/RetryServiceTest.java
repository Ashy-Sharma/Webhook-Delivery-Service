package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.dto.DeliveryMessage;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryServiceTest {

    private DeliveryService deliveryService;
    private ConfirmedRabbitPublisher rabbitPublisher;
    private RetryService retryService;

    @BeforeEach
    void setUp() {
        RetryConfig retryConfig = new RetryConfig();
        ReflectionTestUtils.setField(retryConfig, "delays", java.util.List.of(2_000L, 10_000L, 30_000L));
        deliveryService = mock(DeliveryService.class);
        rabbitPublisher = mock(ConfirmedRabbitPublisher.class);
        retryService = new RetryService(retryConfig, deliveryService, rabbitPublisher);
        ReflectionTestUtils.setField(retryService, "exchange", "webhook.exchange");
        ReflectionTestUtils.setField(retryService, "dlqQueueName", "webhook.dlq");
        ReflectionTestUtils.setField(retryService, "maxInfrastructureDeferralAgeMillis", 300_000L);
    }

    @Test
    void publishesCrashExhaustedMessageBeforePersistingTerminalFailure() {
        Delivery delivery = Delivery.builder().id(41L).attemptCount(5).build();
        Webhook webhook = Webhook.builder().id(8L).url("https://example.com/hook").build();
        DeliveryService.DeliveryAttempt attempt =
                new DeliveryService.DeliveryAttempt(delivery, webhook, 5, java.time.Instant.now());
        DeliveryMessage original = DeliveryMessage.builder().deliveryId(41L).webhookId(8L)
                .eventType("order.created").payload("{\"id\":1}").attemptNumber(5).build();
        when(deliveryService.failCrashExhaustedDelivery(attempt)).thenReturn(true);

        RetryService.RetryOutcome outcome = retryService.handleCrashExhaustion(attempt, original);

        assertEquals(RetryService.RetryOutcome.CRASH_EXHAUSTED_DEAD_LETTERED, outcome);
        var order = inOrder(rabbitPublisher, deliveryService);
        order.verify(rabbitPublisher).publish(eq("webhook.exchange"), eq("webhook.dlq"), any(DeliveryMessage.class));
        order.verify(deliveryService).failCrashExhaustedDelivery(attempt);
        verify(rabbitPublisher).publish(eq("webhook.exchange"), eq("webhook.dlq"), any(DeliveryMessage.class));
    }

    @Test
    void doesNotPersistCrashExhaustionWhenDlqPublishFails() {
        Delivery delivery = Delivery.builder().id(41L).attemptCount(5).build();
        Webhook webhook = Webhook.builder().id(8L).url("https://example.com/hook").build();
        DeliveryService.DeliveryAttempt attempt =
                new DeliveryService.DeliveryAttempt(delivery, webhook, 5, java.time.Instant.now());
        DeliveryMessage original = DeliveryMessage.builder().deliveryId(41L).webhookId(8L)
                .eventType("order.created").payload("{\"id\":1}").attemptNumber(5).build();
        org.mockito.Mockito.doThrow(new IllegalStateException("publisher confirm timed out"))
                .when(rabbitPublisher).publish(eq("webhook.exchange"), eq("webhook.dlq"), any(DeliveryMessage.class));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> retryService.handleCrashExhaustion(attempt, original));

        verify(deliveryService, never()).failCrashExhaustedDelivery(attempt);
    }

    @Test
    void defersInfrastructureFailureThroughLongestRetryTier() {
        DeliveryMessage original = DeliveryMessage.builder().deliveryId(41L).webhookId(8L)
                .eventType("order.created").payload("{\"id\":1}").attemptNumber(3).build();

        assertEquals(false, retryService.deferInfrastructureFailure(original, null));

        org.mockito.ArgumentCaptor<DeliveryMessage> message =
                org.mockito.ArgumentCaptor.forClass(DeliveryMessage.class);
        verify(rabbitPublisher).publish(eq("webhook.exchange"), eq("webhook.retry.tier3"), message.capture());
        assertEquals(41L, message.getValue().getDeliveryId());
        assertEquals(3, message.getValue().getAttemptNumber());
        org.junit.jupiter.api.Assertions.assertNotNull(message.getValue().getInfrastructureDeferredSince());
        verify(deliveryService).recordInfrastructureDeferral(
                eq(41L), eq(message.getValue().getInfrastructureDeferredSince()));
    }

    @Test
    void expiredDeferralGoesToDlqOnlyWhenAnotherInfrastructureFailureOccurs() {
        Delivery delivery = Delivery.builder().id(41L).attemptCount(1).build();
        Webhook webhook = Webhook.builder().id(8L).url("https://example.com/hook").build();
        DeliveryService.DeliveryAttempt attempt =
                new DeliveryService.DeliveryAttempt(delivery, webhook, 1, java.time.Instant.now());
        DeliveryMessage original = DeliveryMessage.builder().deliveryId(41L).webhookId(8L)
                .eventType("order.created").payload("{\"id\":1}").attemptNumber(1)
                .infrastructureDeferredSince(java.time.Instant.now().minusSeconds(360)).build();
        when(deliveryService.failInfrastructureDeferredDelivery(attempt)).thenReturn(true);

        assertEquals(true, retryService.deferInfrastructureFailure(original, attempt));

        var order = inOrder(rabbitPublisher, deliveryService);
        order.verify(rabbitPublisher).publish(eq("webhook.exchange"), eq("webhook.dlq"), any(DeliveryMessage.class));
        order.verify(deliveryService).failInfrastructureDeferredDelivery(attempt);
        verify(rabbitPublisher, never()).publish(eq("webhook.exchange"), eq("webhook.retry.tier3"),
                any(DeliveryMessage.class));
    }
}
