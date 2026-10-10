# Webhook Delivery Service

Spring Boot service for registering webhooks, publishing events, and delivering
signed HTTP callbacks with retries, rate limiting, and delivery history.

## Build and tests

Compile the application and tests:

```bash
./mvnw -q -DskipTests test-compile
```

Run the focused service, worker, and MySQL integration tests:

```bash
./mvnw -q -Dtest=WebhookRepositoryIntegrationTest,DeliveryServiceTest,ConfirmedRabbitPublisherTest,DeliveryWorkerTest,RateLimitServiceTest test
```

The opt-in end-to-end rate-limit load test starts temporary MySQL, Redis, and
RabbitMQ containers, then delivers 100 messages to a local HTTP receiver:

```bash
./mvnw -q -Dtest=RateLimitLoadIntegrationTest test
```

## Rate-limit backlog measurement

On 2026-10-11, the end-to-end test delivered **100 of 100 webhook requests in
240.8 seconds**, an average of **0.415 deliveries per second**. The test used one
worker consumer, the configured limit of 30 deliveries per 60-second sliding
window, and retry tiers of 2, 10, and 30 seconds. It measured enqueue-to-final
delivery time, including rate-limit waits and reconciliation of messages that
arrived before their database retry deadline. The receiver ran locally in the
test process; MySQL 8.4, Redis 7, and RabbitMQ 4.1 ran in Testcontainers.

This is a local development measurement, not a production capacity guarantee.
Throughput is 0.415/s against a 0.5/s ideal because throttled messages wait in fixed-TTL tiers, not until the exact slot opens.
The fixed-TTL retry queues still create synchronized retry batches; use the load
test to compare future scheduling changes under the same settings.
