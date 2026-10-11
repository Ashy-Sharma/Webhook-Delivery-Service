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

## Day 5 admin operations

Manual retry and event replay endpoints require an account with the `ADMIN` role:

- `POST /api/admin/deliveries/{deliveryId}/retry`
- `POST /api/admin/events/{eventId}/replay`

Registration intentionally creates `USER` accounts only. For local development,
promote a test account directly in MySQL after registering it:

```sql
UPDATE users SET role = 'ADMIN' WHERE email = 'your-test-email@example.com';
```

Log in again after the role change so the access token contains the `ADMIN`
authority. Redis counters under `stats:delivery:*` and `stats:webhook:{id}:*`
are best-effort summaries of terminal HTTP outcomes; MySQL deliveries and logs
remain the source of truth.

## Day 6 monitoring APIs

The event catalog is configured with `webhook.event-types`. Webhook creation,
webhook event-type updates, and new event publication reject types outside this
list with HTTP 400; replaying an existing event does not revalidate its type.
`GET /api/event-types` returns the configured list to authenticated users.

Admin monitoring endpoints require the `ADMIN` role:

- `GET /api/admin/dashboard` reports current MySQL delivery rows separately
  from Redis terminal outcome counters. `retryingDeliveries` is the current
  `RETRYING` row count; status buckets are not expected to sum to all rows.
- `GET /api/admin/webhooks/health` reports scores based on Redis terminal
  success/failure outcome counters and the average of the last 100 successful
  latencies per webhook. The score is an all-time outcome ratio with a latency
  penalty, so it can recover slowly after an endpoint is fixed.
- `GET /api/admin/webhooks/{id}/deliveries` returns paged delivery history;
  page size is capped at 100 and latest attempt summaries are batch-loaded.
- `GET /api/admin/deliveries/{id}/logs` returns attempt details with a 200
  character request-body preview. Add `?includeBody=true` to include the full
  stored request body.

The dashboard's global latency is the average of non-null response times for
2xx HTTP attempts from the last 24 hours. `delivery_logs.created_at` is indexed
for this bounded query. `permanentlyFailedDeliveries` is a current MySQL row
count for DLQ-routed failure reasons; worker-crash and throttle-age values are
breakdowns within it. `stuckDeliveries` counts `PENDING` rows at the reconciliation
attempt cap. These counts are not RabbitMQ queue depths. Per-webhook rate-limit
usage counts only ZSET scores in the configured active window.

The Day 6 Postman checks are in `postman_tests/webhook-day6.postman_collection.json`.
The `postman_tests/` directory is git-ignored in this workspace.
