package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.Event;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.EventRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventReplayServiceTest {

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final WebhookRepository webhookRepository = mock(WebhookRepository.class);
    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final ConfirmedRabbitPublisher rabbitPublisher = mock(ConfirmedRabbitPublisher.class);
    private final RetryConfig retryConfig = new RetryConfig();
    private final EventService eventService = new EventService(eventRepository, webhookRepository,
            deliveryRepository, rabbitPublisher, retryConfig, new ObjectMapper());

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void replayCreatesFreshPendingDeliveriesForCurrentlyActiveMatchingWebhooks() {
        ReflectionTestUtils.setField(retryConfig, "maxAttempts", 5);
        ReflectionTestUtils.setField(eventService, "exchange", "webhook.exchange");
        ReflectionTestUtils.setField(eventService, "routingKey", "webhook.deliver");
        Event event = Event.builder().id(15L).eventType("order.created").payload("{\"id\":1}").build();
        Webhook first = Webhook.builder().id(21L).build();
        Webhook second = Webhook.builder().id(22L).build();
        when(eventRepository.findById(15L)).thenReturn(Optional.of(event));
        when(webhookRepository.findActiveWebhooksByEventType("order.created"))
                .thenReturn(List.of(first, second));
        AtomicLong id = new AtomicLong(100);
        when(deliveryRepository.save(any())).thenAnswer(invocation -> {
            var delivery = invocation.<com.projects.webhookdeliveryservice.entity.Delivery>getArgument(0);
            delivery.setId(id.incrementAndGet());
            return delivery;
        });

        TransactionSynchronizationManager.initSynchronization();
        var result = eventService.replayEvent(15L);

        assertThat(result.getEventId()).isEqualTo(15L);
        assertThat(result.getEventType()).isEqualTo("order.created");
        assertThat(result.getWebhooksMatched()).isEqualTo(2);
        assertThat(result.getDeliveriesCreated()).isEqualTo(2);
        verify(deliveryRepository, times(2)).save(any());

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(2);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(rabbitPublisher, times(2)).publish(eq("webhook.exchange"), eq("webhook.deliver"), any());
    }
}
