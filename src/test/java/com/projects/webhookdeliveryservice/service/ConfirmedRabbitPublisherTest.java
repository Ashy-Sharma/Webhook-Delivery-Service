package com.projects.webhookdeliveryservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ConfirmedRabbitPublisherTest {

    private RabbitTemplate rabbitTemplate;
    private ConfirmedRabbitPublisher publisher;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        publisher = new ConfirmedRabbitPublisher(rabbitTemplate);
        ReflectionTestUtils.setField(publisher, "confirmTimeoutMillis", 100L);
    }

    @Test
    void acceptsConfirmedRoutedMessage() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq("events"), eq("delivery"), eq("payload"),
                any(CorrelationData.class));

        assertDoesNotThrow(() -> publisher.publish("events", "delivery", "payload"));
    }

    @Test
    void rejectsNegativePublisherConfirm() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker rejected"));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq("events"), eq("delivery"), eq("payload"),
                any(CorrelationData.class));

        assertThrows(IllegalStateException.class, () -> publisher.publish("events", "delivery", "payload"));
    }

    @Test
    void rejectsReturnedUnroutableMessageEvenWhenBrokerConfirmsIt() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[0]), 312, "NO_ROUTE", "events", "delivery"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq("events"), eq("delivery"), eq("payload"),
                any(CorrelationData.class));

        assertThrows(IllegalStateException.class, () -> publisher.publish("events", "delivery", "payload"));
    }
}
