package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.dto.DeliveryMessage;
import com.projects.webhookdeliveryservice.dto.request.PublishEventRequest;
import com.projects.webhookdeliveryservice.dto.response.EventPublishResponse;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.Event;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.EventRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final WebhookRepository webhookRepository;
    private final DeliveryRepository deliveryRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    @Value("${webhook.rabbitmq.exchange}")
    private String exchange;

    @Value("${webhook.rabbitmq.delivery-routing-key}")
    private String routingKey;

    @Transactional
    public EventPublishResponse publishEvent(PublishEventRequest request) {

        Event event = Event.builder()
                .eventType(request.getEventType())
                .payload(serializePayload(request))
                .source(request.getSource())
                .build();
        Event savedEvent = eventRepository.save(event);

        List<Webhook> matchingWebhooks = webhookRepository
                .findActiveWebhooksByEventType(request.getEventType());

        for (Webhook webhook : matchingWebhooks) {
            Delivery delivery = deliveryRepository.save(Delivery.builder()
                    .event(savedEvent)
                    .webhook(webhook)
                    .status(DeliveryStatus.PENDING)
                    .attemptCount(0)
                    .maxAttempts(5)
                    .build());

            publishAfterCommit(delivery);
        }

        return EventPublishResponse.builder()
                .eventId(savedEvent.getId())
                .eventType(savedEvent.getEventType())
                .webhooksMatched(matchingWebhooks.size())
                .deliveriesCreated(matchingWebhooks.size())
                .build();
    }

    private String serializePayload(PublishEventRequest request) {
        try {
            return objectMapper.writeValueAsString(request.getPayload());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Event payload could not be serialized", exception);
        }
    }

    public void publishDelivery(Delivery delivery) {
        Event event = delivery.getEvent();
        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(delivery.getId())
                .webhookId(delivery.getWebhook().getId())
                .eventType(event.getEventType())
                .payload(event.getPayload())
                .attemptNumber(delivery.getAttemptCount() + 1)
                .build();
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }


    public void publishAfterCommit(Delivery delivery){
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try {
                            publishDelivery(delivery);
                        } catch (Exception e) {
                            log.error("Publish failed for delivery {}; reconciler will retry", delivery.getId(), e);
                        }
                    }
                }
        );
    }

}
