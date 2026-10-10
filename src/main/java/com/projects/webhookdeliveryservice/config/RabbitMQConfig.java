package com.projects.webhookdeliveryservice.config;

import com.projects.webhookdeliveryservice.service.RetryConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Bean
    public DirectExchange webhookExchange(@Value("${webhook.rabbitmq.exchange}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Queue deliveryQueue(
            @Value("${webhook.rabbitmq.delivery-queue}") String queueName,
            @Value("${webhook.rabbitmq.exchange}") String exchange,
            @Value("${webhook.rabbitmq.poison-routing-key:webhook.poison}") String poisonRoutingKey) {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(exchange)
                .deadLetterRoutingKey(poisonRoutingKey)
                .build();
    }

    @Bean
    public Binding deliveryBinding(
            @Qualifier("deliveryQueue") Queue deliveryQueue,
            DirectExchange webhookExchange,
            @Value("${webhook.rabbitmq.delivery-routing-key}") String routingKey) {
        return BindingBuilder.bind(deliveryQueue).to(webhookExchange).with(routingKey);
    }

    @Bean
    public Queue retryTier1Queue(RetryConfig retryConfig,
                              @Value("${webhook.rabbitmq.exchange}") String exchange,
                              @Value("${webhook.rabbitmq.delivery-routing-key}") String deliveryRoutingKey) {
        return retryQueue(retryConfig.getRetryQueueNameForTier(1), retryConfig.getDelayMillis(0),
                exchange, deliveryRoutingKey);
    }

    @Bean
    public Queue retryTier2Queue(RetryConfig retryConfig,
                               @Value("${webhook.rabbitmq.exchange}") String exchange,
                               @Value("${webhook.rabbitmq.delivery-routing-key}") String deliveryRoutingKey) {
        return retryQueue(retryConfig.getRetryQueueNameForTier(2), retryConfig.getDelayMillis(1),
                exchange, deliveryRoutingKey);
    }

    @Bean
    public Queue retryTier3Queue(RetryConfig retryConfig,
                               @Value("${webhook.rabbitmq.exchange}") String exchange,
                               @Value("${webhook.rabbitmq.delivery-routing-key}") String deliveryRoutingKey) {
        return retryQueue(retryConfig.getRetryQueueNameForTier(3), retryConfig.getDelayMillis(2),
                exchange, deliveryRoutingKey);
    }

    @Bean
    public Queue deadLetterQueue(@Value("${webhook.rabbitmq.dlq-queue:webhook.dlq}") String queueName) {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public Queue poisonQueue(@Value("${webhook.rabbitmq.poison-queue:webhook.poison}") String queueName) {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public Binding retryTier1Binding(@Qualifier("retryTier1Queue") Queue queue,
                                     DirectExchange webhookExchange, RetryConfig retryConfig) {
        return BindingBuilder.bind(queue).to(webhookExchange).with(retryConfig.getRetryRoutingKeyForTier(1));
    }

    @Bean
    public Binding retryTier2Binding(@Qualifier("retryTier2Queue") Queue queue,
                                     DirectExchange webhookExchange, RetryConfig retryConfig) {
        return BindingBuilder.bind(queue).to(webhookExchange).with(retryConfig.getRetryRoutingKeyForTier(2));
    }

    @Bean
    public Binding retryTier3Binding(@Qualifier("retryTier3Queue") Queue queue,
                                     DirectExchange webhookExchange, RetryConfig retryConfig) {
        return BindingBuilder.bind(queue).to(webhookExchange).with(retryConfig.getRetryRoutingKeyForTier(3));
    }

    @Bean
    public Binding deadLetterBinding(@Qualifier("deadLetterQueue") Queue queue, DirectExchange webhookExchange) {
        return BindingBuilder.bind(queue).to(webhookExchange).with("webhook.dlq");
    }

    @Bean
    public Binding poisonBinding(@Qualifier("poisonQueue") Queue queue, DirectExchange webhookExchange,
                                 @Value("${webhook.rabbitmq.poison-routing-key:webhook.poison}") String routingKey) {
        return BindingBuilder.bind(queue).to(webhookExchange).with(routingKey);
    }

    private Queue retryQueue(String name, long ttlMillis, String exchange, String routingKey) {
        return QueueBuilder.durable(name)
                .withArgument("x-message-ttl", Math.toIntExact(ttlMillis))
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", routingKey)
                .build();
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
