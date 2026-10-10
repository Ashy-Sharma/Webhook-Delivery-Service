package com.projects.webhookdeliveryservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
public class ConfirmedRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${webhook.rabbitmq.publish-confirm-timeout-ms:5000}")
    private long confirmTimeoutMillis;

    public void publish(String exchange, String routingKey, Object payload) {
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(exchange, routingKey, payload, correlationData);

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ negatively acknowledged publish: " + confirm.reason());
            }

            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new IllegalStateException("RabbitMQ returned unroutable message (reply code "
                        + returned.getReplyCode() + "): " + returned.getReplyText());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for RabbitMQ publisher confirm", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("RabbitMQ publisher confirm failed or timed out", exception);
        }
    }
}
