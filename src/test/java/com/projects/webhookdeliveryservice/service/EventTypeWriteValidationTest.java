package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.dto.request.CreateWebhookRequest;
import com.projects.webhookdeliveryservice.dto.request.PublishEventRequest;
import com.projects.webhookdeliveryservice.dto.request.UpdateWebhookRequest;
import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.exception.InvalidEventTypeException;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.EventRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventTypeWriteValidationTest {

    private final EventTypeService eventTypes = new EventTypeService("order.created,user.signup");

    @Test
    void createWebhookRejectsUnlistedTypeBeforeSaving() {
        WebhookRepository webhooks = mock(WebhookRepository.class);
        WebhookService service = new WebhookService(webhooks, new ObjectMapper(), mock(DeliveryRepository.class), eventTypes);
        CreateWebhookRequest request = CreateWebhookRequest.builder().url("https://receiver.test/hook")
                .eventTypes(List.of("ordr.created")).build();

        assertThatThrownBy(() -> service.createWebhook(request, User.builder().build()))
                .isInstanceOf(InvalidEventTypeException.class)
                .hasMessageContaining("Allowed types: [order.created, user.signup]");
        verifyNoInteractions(webhooks);
    }

    @Test
    void updateWebhookRejectsUnlistedTypeAndDoesNotSave() {
        WebhookRepository webhooks = mock(WebhookRepository.class);
        User owner = User.builder().id(2L).build();
        Webhook existing = Webhook.builder().id(8L).owner(owner).url("https://receiver.test/hook")
                .eventType("[\"order.created\"]").secretKey("secret").build();
        when(webhooks.findByIdAndOwnerAndDeletedAtIsNull(8L, owner)).thenReturn(Optional.of(existing));
        WebhookService service = new WebhookService(webhooks, new ObjectMapper(), mock(DeliveryRepository.class), eventTypes);
        UpdateWebhookRequest request = UpdateWebhookRequest.builder().eventTypes(List.of("unknown.type")).build();

        assertThatThrownBy(() -> service.updateWebhook(8L, request, owner))
                .isInstanceOf(InvalidEventTypeException.class);
        verify(webhooks, never()).save(existing);
    }

    @Test
    void newEventRejectsUnlistedTypeButReplayDoesNotRevalidateHistoricalType() {
        EventRepository events = mock(EventRepository.class);
        EventService service = new EventService(events, mock(WebhookRepository.class),
                mock(DeliveryRepository.class), mock(ConfirmedRabbitPublisher.class), new RetryConfig(),
                new ObjectMapper(), eventTypes);
        PublishEventRequest request = PublishEventRequest.builder().eventType("unlisted.type")
                .payload(Map.of("value", 1)).build();

        assertThatThrownBy(() -> service.publishEvent(request))
                .isInstanceOf(InvalidEventTypeException.class);
        verifyNoInteractions(events);
    }
}
