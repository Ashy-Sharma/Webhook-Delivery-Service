# Webhook Delivery Service

A backend service for accepting application events and delivering them asynchronously to registered subscriber URLs. It provides owner-scoped webhook management, HMAC-signed HTTP requests, retry and rate-limit handling, administrative replay and monitoring endpoints, and persistent delivery history.

## Contents

- [Capabilities](#capabilities)
- [Architecture and delivery lifecycle](#architecture-and-delivery-lifecycle)
- [Technology](#technology)
- [Run locally with Docker Compose](#run-locally-with-docker-compose)
- [Configuration](#configuration)
- [API guide](#api-guide)
- [Webhook signature verification](#webhook-signature-verification)
- [Monitoring definitions](#monitoring-definitions)
- [Tests](#tests)
- [Troubleshooting](#troubleshooting)
- [Known limitations and future work](#known-limitations-and-future-work)

## Capabilities

- Register and authenticate users with short-lived JWT access tokens and refresh tokens.
- Create, list, update, delete, and rotate secrets for webhooks owned by the authenticated user.
- Publish events that are matched to active subscriptions for the event type.
- Deliver events in the background through RabbitMQ, with HMAC-SHA256 signatures and delivery metadata headers.
- Retry failed HTTP attempts on delayed tiers, enforce a per-webhook sliding-window rate limit, and route terminal/poison messages to broker dead-letter queues.
- Recover eligible pending deliveries and expired worker claims with scheduled reconciliation.
- Inspect delivery history, individual attempt logs, webhook statistics, endpoint health, and platform metrics.
- Run the API and its MySQL, Redis, and RabbitMQ dependencies using Docker Compose.

## Architecture and delivery lifecycle

```text
Client
  │ REST + JWT
  ▼
Spring Boot API ─────────────► MySQL / Flyway
  │                              users, webhooks, events,
  │                              deliveries, attempt logs
  ├──────────────────────────► Redis
  │                              rate-limit windows and
  │                              best-effort monitoring stats
  └──────────────────────────► RabbitMQ
                                   │ delivery/retry/DLQ queues
                                   ▼
                              Delivery worker ── signed HTTP ──► Subscriber
```

MySQL is the source of truth for users, webhooks, events, delivery status, and attempt audit rows. RabbitMQ transports asynchronous delivery work. Redis stores rate-limit windows and best-effort counters/latency samples used by monitoring. Flyway migrations manage the MySQL schema; Hibernate validates the mapped schema at startup.

For a newly published event, the API validates its type, stores it, finds active webhooks subscribed to that type, creates a delivery row for each match, and publishes delivery messages. A worker claims a delivery with a status-and-claim-time fence, checks rate limiting, signs and sends the event payload, records the attempt, and completes or schedules the delivery. A successful subscriber response is any HTTP 2xx response. Failures are retried according to configured limits and delay tiers; terminal failures and malformed broker messages are routed to dead-letter/poison queues. Scheduled reconciliation repairs eligible rows when publication or worker processing is interrupted.

Delivery states used by the service are `PENDING`, `DELIVERING`, `DELIVERED`, `RETRYING`, `FAILED`, and `CANCELLED`. Attempt counts in MySQL represent actual HTTP attempts; rate-limit deferrals refund the temporary claim increment and do not create an HTTP attempt.

### Retry and recovery behavior

- Default maximum HTTP attempts: 5.
- Retry delay tiers: 2 seconds, 10 seconds, and 30 seconds. The last tier is reused for later retries.
- Worker claims are fenced by delivery status and claim timestamp so an expired/recovered claim cannot be overwritten by an older worker.
- Per-webhook rate limiting defaults to 30 requests per 60-second sliding window. Throttled work is deferred without treating the deferral as an HTTP attempt.
- Long-lived throttling and infrastructure deferrals have separate configured age limits and terminal failure reasons.
- Rabbit listener acknowledgements are manual. Infrastructure persistence/publish errors are deferred/requeued with backoff rather than silently acknowledged.

## Technology

- Java 17
- Spring Boot 4.1, Spring MVC, Spring Security, Spring Data JPA, Spring AMQP, Spring Data Redis, and Actuator
- MySQL 8.4 with Flyway migrations
- RabbitMQ 4.1 (management image in Compose)
- Redis 7
- Maven Wrapper
- JUnit 5, Mockito, Spring test support, and Testcontainers
- Docker and Docker Compose

## Run locally with Docker Compose

Requirements: Docker Engine and the Docker Compose plugin. The Compose stack starts MySQL, Redis, RabbitMQ, and the API.

```bash
cp .env.example .env
openssl rand -base64 32
```

Copy the generated value into the `JWT_SECRET` line in `.env`, then start the stack:

```bash
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

Default local URLs:

| Service | Address | Notes |
|---|---|---|
| API | `http://localhost:8080` | REST API |
| Health | `http://localhost:8080/actuator/health` | Public Actuator health endpoint |
| RabbitMQ Management | `http://localhost:15672` | Credentials come from `.env` |
| MySQL | `localhost:3306` | Database defaults to `webhook` |
| Redis | `localhost:6379` | Redis has no password in the local Compose configuration |

The application waits for dependency health checks before starting. Flyway runs migrations automatically on application startup. Named volumes preserve database, Redis, and RabbitMQ data across normal `docker compose down` and `up` cycles. To remove the local persisted data as well, run `docker compose down -v` (this deletes the Compose volumes).

If a host port is already occupied, change the corresponding variable in `.env`: `APP_PORT`, `MYSQL_PORT`, `REDIS_PORT`, `RABBITMQ_PORT`, or `RABBITMQ_MANAGEMENT_PORT`. Container-to-container ports remain unchanged.

The sample credentials and JWT key in `.env.example` are for local development only. Keep `.env` private; it is gitignored.

### Run against services installed locally

The checked-in `application.properties` contains local development defaults. Start MySQL on `localhost:3306`, Redis on `localhost:6379`, and RabbitMQ on `localhost:5672`, then run:

```bash
./mvnw spring-boot:run
```

Set the required environment variables (`JWT_SECRET`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, and `RABBITMQ_PASSWORD`) for a non-default setup. `src/main/resources/application.properties.example` is a configuration reference. Docker Compose supplies container hostnames and credentials to the app automatically.

## Configuration

Important properties in `application.properties` include:

| Property | Default | Purpose |
|---|---:|---|
| `jwt.expiration-ms` | `900000` (15 min) | Access-token lifetime |
| `jwt.refresh-expiration-ms` | `604800000` (7 days) | Refresh-token lifetime |
| `webhook.event-types` | `order.created,order.updated,user.signup,payment.failed` | Event-type allowlist for webhook writes and new event publication |
| `webhook.retry.max-attempts` | `5` | Maximum HTTP attempts per delivery |
| `webhook.retry.delays` | `2000,10000,30000` | Fixed retry delays in milliseconds |
| `webhook.ratelimit.max-per-minute` | `30` | Requests allowed per webhook in the configured sliding window |
| `webhook.ratelimit.window-ms` | `60000` | Rate-limit window in milliseconds |
| `webhook.ratelimit.max-throttle-age-ms` | `3600000` (1 hour) | Maximum accumulated throttle duration before terminal handling |
| `webhook.infrastructure.max-deferral-age-ms` | `300000` (5 min) | Infrastructure deferral age limit |
| `spring.data.redis.timeout` | `500ms` | Redis operation timeout |
| `spring.rabbitmq.listener.simple.prefetch` | `1` | Unacknowledged messages per listener consumer |

Event types are configured through `webhook.event-types`. The API exposes the active allowlist at `GET /api/event-types`. Creating or updating a webhook validates supplied event types; publishing a new event validates its type as well. Replaying a previously stored event does not revalidate it, so historical events remain replayable if the allowlist changes later. Existing subscriptions are not retroactively invalidated.

## API guide

Unless marked public, API routes require `Authorization: Bearer <accessToken>`. `/api/admin/**` requires the `ADMIN` role. Webhook CRUD and webhook stats are scoped to the authenticated owner. The health endpoint is public for container health checks.

### Authentication

| Method | Path | Access | Result |
|---|---|---|---|
| `POST` | `/api/auth/register` | Public | `201`; creates a user and returns tokens |
| `POST` | `/api/auth/login` | Public | `200`; returns access and refresh tokens |
| `POST` | `/api/auth/refresh` | Public | `200`; refreshes/rotates a refresh-token session |
| `POST` | `/api/auth/logout` | Authenticated | `204`; revokes the supplied refresh session and current access token |
| `POST` | `/api/auth/logout-all` | Authenticated | `204`; revokes the user's sessions |

Registration requires an email, a username of 3–50 letters/numbers/underscores, and a password of 8–100 characters containing uppercase, lowercase, and numeric characters.

```bash
curl -i http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"dev@example.com","username":"dev_user","password":"StrongPass123"}'
```

The token response has `accessToken`, `refreshToken`, and `tokenType` fields. Use the access token as a bearer token for subsequent requests.

### Webhooks and events

| Method | Path | Access | Result |
|---|---|---|---|
| `POST` | `/api/webhooks` | Authenticated owner | `201`; creates a webhook and returns its secret |
| `GET` | `/api/webhooks` | Authenticated owner | Lists the caller's webhooks |
| `GET` | `/api/webhooks/{id}` | Authenticated owner | Reads one owned webhook |
| `PUT` | `/api/webhooks/{id}` | Authenticated owner | Updates supplied webhook fields |
| `DELETE` | `/api/webhooks/{id}` | Authenticated owner | `204`; deletes an owned webhook |
| `POST` | `/api/webhooks/{id}/regenerate-secret` | Authenticated owner | Rotates the signing secret |
| `GET` | `/api/webhooks/{id}/stats` | Authenticated owner | Reads that webhook's stats and recent deliveries |
| `GET` | `/api/event-types` | Authenticated | Lists configured event types |
| `POST` | `/api/events` | Authenticated | `201`; stores an event and creates deliveries for matching active webhooks |

Webhook creation requires a valid URL and a non-empty `eventTypes` array. `description` is optional. `PUT` accepts partial fields (`url`, `eventTypes`, `description`, `isActive`); event types are validated if included. The returned webhook representation includes `secretKey`; store it securely. Regenerating a secret invalidates the previous key for future signatures.

```bash
export API=http://localhost:8080
export ACCESS_TOKEN='paste-access-token-here'

curl -i "$API/api/webhooks" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://subscriber.example.com/hooks/orders","eventTypes":["order.created"],"description":"Order notifications"}'
```

Publish an event using a type from `GET /api/event-types`:

```bash
curl -i "$API/api/events" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"order.created","payload":{"orderId":"ord_123","total":42.50},"source":"checkout"}'
```

The response includes `eventId`, `eventType`, `webhooksMatched`, and `deliveriesCreated`. `payload` is the event body sent to matching subscribers. An accepted event can have zero matches when no active webhook subscribes to its type.

### Administration and inspection

| Method | Path | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/admin/deliveries/{deliveryId}/retry` | Admin | Manually retries an eligible failed delivery |
| `POST` | `/api/admin/events/{eventId}/replay` | Admin | Replays an existing event to current matching webhooks |
| `GET` | `/api/admin/dashboard` | Admin | Platform-wide monitoring summary |
| `GET` | `/api/admin/webhooks/health` | Admin | Lists webhook health and rate-limit usage |
| `GET` | `/api/admin/webhooks/{webhookId}/deliveries` | Admin | Lists a webhook's delivery history |
| `GET` | `/api/admin/deliveries/{deliveryId}/logs` | Admin | Lists attempt logs; `includeBody=true` includes stored request bodies |
| `GET` | `/actuator/health` | Public | Application/dependency health groups for operational checks |

Delivery history supports optional `status`, `page` (default `0`), and `size` (default `20`, maximum `100`) query parameters. For example:

```bash
curl "$API/api/admin/webhooks/12/deliveries?status=FAILED&page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"
curl "$API/api/admin/deliveries/42/logs?includeBody=true" \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"
```

Admin endpoints require a user whose persisted role is `ADMIN`. Registration creates a regular user; admin provisioning is an operational/database responsibility in the current implementation.

## Webhook signature verification

Each outbound callback uses the stored webhook secret to calculate an HMAC-SHA256 over the exact UTF-8 payload string sent as the request body. The hexadecimal signature and delivery metadata are sent in these headers:

| Header | Meaning |
|---|---|
| `X-Webhook-Signature` | Hex-encoded HMAC-SHA256 signature of the raw request body |
| `X-Webhook-Event` | Event type |
| `X-Webhook-Delivery-Id` | Delivery row identifier; useful for subscriber-side idempotency |
| `X-Webhook-Timestamp` | Unix timestamp in seconds, generated for the outbound request |
| `Content-Type` | `application/json` |

Verify the signature against the raw body bytes before parsing and reserializing JSON. Re-serialization may change whitespace or key order and therefore produce a different HMAC. Use `X-Webhook-Delivery-Id` to deduplicate callbacks: retries and crash recovery can result in the same logical delivery reaching a subscriber more than once.

## Monitoring definitions

The service exposes three related monitoring views: the platform dashboard (`GET /api/admin/dashboard`), active webhook health (`GET /api/admin/webhooks/health`), and owner-scoped webhook stats (`GET /api/webhooks/{id}/stats`). Dashboard values combine MySQL state with Redis counters. Read the source and time window of each metric before comparing or summing values.

### Platform dashboard metrics

| Field | Source and calculation | How to interpret it |
|---|---|---|
| `totalDeliveries` | Count of delivery rows in MySQL. | Lifetime row count, including rows in any delivery status. It is not a count of HTTP requests. |
| `successfulDeliveries` | Global Redis success counter. Incremented when a delivery is persisted as successful after a 2xx response. | Recorded success outcome cycles, not necessarily unique delivery rows. Best effort; Redis loss resets visibility unless counters are rebuilt. |
| `failedDeliveries` | Global Redis failure counter. Incremented when HTTP retry exhaustion is recorded. | Recorded exhausted HTTP outcome cycles; does not include every `FAILED` row. For example, throttle expiry and worker crash exhaustion are intentionally not subscriber HTTP failures. |
| `successRate` | `successes / (successes + failures) * 100`, from the two global Redis counters. Returns `0` when there are no recorded outcomes. | Redis outcome-cycle success percentage. It is not calculated from all MySQL delivery rows or individual HTTP attempts. |
| `retryingDeliveries` | MySQL count of rows with status `RETRYING`. | Current-state gauge: deliveries waiting on or progressing through a retry, not a historical retry total. |
| `activeWebhooks` | Number of active, non-deleted webhooks loaded for health calculations. | Current active endpoint count. |
| `healthyWebhooks` | Active webhooks with health score at least `80`. | Current endpoint count; a webhook with no recorded outcomes starts at score `100`. |
| `degradedWebhooks` | Active webhooks with score `50` through `79`. | Current endpoint count. |
| `unhealthyWebhooks` | Active webhooks with score below `50`. | Current endpoint count. |
| `permanentlyFailedDeliveries` | MySQL count of `FAILED` rows with failure reason `HTTP_EXHAUSTED`, `WORKER_CRASH_EXHAUSTED`, `THROTTLE_AGE_EXCEEDED`, or `INFRASTRUCTURE_DEFERRAL_EXHAUSTED`. | Current database rows in terminal DLQ failure categories; this is not the RabbitMQ DLQ queue depth. Manual retry may reset a row while a broker message remains. |
| `workerCrashExhaustedDeliveries` | MySQL count of `FAILED` rows with reason `WORKER_CRASH_EXHAUSTED`. | Breakdown of `permanentlyFailedDeliveries`, not an additional disjoint total. |
| `throttleExpiredDeliveries` | MySQL count of `FAILED` rows with reason `THROTTLE_AGE_EXCEEDED`. | Breakdown of permanent failures caused by extended rate-limit deferral. No HTTP request was sent for the throttle deferral itself. |
| `stuckDeliveries` | MySQL count of `PENDING` rows whose reconciliation-attempt count is at least `5`. | Rows repeatedly eligible for reconciliation but not successfully progressed. This is a current operational backlog indicator. |
| `averageLatencyMs` | MySQL average of non-null response times for successful 2xx attempts created during the last 24 hours. | Recent successful HTTP response latency, not total delivery time and not failure/timeout duration. Returns `0` when no samples match. |

The status and reason fields describe current MySQL row state, while Redis counters describe recorded outcomes over the lifetime of the Redis keys. Consequently, `successfulDeliveries + failedDeliveries` need not equal `totalDeliveries`; status buckets also do not partition every row into only those two outcomes. The crash-exhausted and throttle-expired fields are subsets of `permanentlyFailedDeliveries`.

### Per-webhook health and statistics

`GET /api/admin/webhooks/health` returns these fields for each active, non-deleted webhook:

| Field | Meaning |
|---|---|
| `webhookId`, `url` | Webhook identity and configured subscriber URL. |
| `successCount`, `failureCount` | Per-webhook Redis outcome-cycle counters. They are best effort, not MySQL row totals or raw attempt counts. |
| `successRate` | `successCount / (successCount + failureCount) * 100`; defaults to `100` with no outcomes. |
| `avgLatencyMs` | Average of up to the latest 100 successful response latency samples stored for this webhook in Redis. It is not the same population/window as the dashboard's 24-hour MySQL average. |
| `healthScore` | Rounded success rate minus a latency penalty, clamped to a minimum of `0`. Penalty is 0 ms for average latency up to 1,000 ms; 2 points above 1,000 ms; 5 points above 2,000 ms; and 10 points above 5,000 ms. With no outcomes, score is `100`. |
| `healthStatus` | `HEALTHY` at score `>= 80`, `DEGRADED` at `50–79`, and `UNHEALTHY` below `50`. |
| `lastDeliveryAt` | Timestamp of the latest delivery-attempt log for the webhook, or null when no attempt log exists. |
| `rateLimitUsage` | Active Redis sliding-window count formatted as `currentUsage/configuredLimit`, normally `n/30`. It is a live-window gauge, not a minute-bucket historical aggregate. |

`GET /api/webhooks/{id}/stats` returns the same outcome counters, success rate, and recent-success latency average for an owned webhook, along with `recentDeliveries`. That list contains at most the 10 newest delivery rows and includes `deliveryId`, `eventType`, current `status`, and the latest attempt's `latencyMs` when available. A delivery may be pending and therefore have no latency yet.

Health scores use accumulated Redis outcome counters rather than a rolling window, so they can move slowly after a subscriber is fixed. Redis statistics are deliberately best effort: Redis read failures produce zero-valued stats for that response, and Redis write failures do not roll back the persisted delivery result. Rate-limit usage is read from sorted-set entries whose scores fall within the current window (`now - window` through `now`); expired entries are excluded from the reported count.

### Delivery history and attempt analytics

Admin delivery history is ordered newest first and supports filtering by status, pagination, and a maximum page size of 100. Each row reports current delivery status and attempt count, plus the latest attempt's response status/error when one exists. Attempt-log inspection is ordered by attempt number and exposes request URL, headers, bounded body preview, response status/body, latency, error text, and timestamp. `includeBody=true` opts into returning the full stored request body.

Response latency is measured around the outbound HTTP exchange. It is distinct from queue wait, retry delay, throttling delay, and end-to-end event-to-delivery time. Attempt log fields such as response status or response time can be null for audit entries that do not represent an HTTP response; consumers should handle nulls.

`permanentlyFailedDeliveries` and its reason breakdowns are MySQL row counts, not live broker queue lengths. Manual retry can move a failed row back into processing while an earlier message remains in the DLQ. A throttled delivery has not made an HTTP attempt; throttle expiry is tracked separately and is not included in subscriber failure health counters.

Delivery attempt logs include request metadata and a bounded response preview. Full request bodies are omitted from admin log responses unless `includeBody=true`; treat those bodies and webhook secrets as sensitive data.

## Tests

Run the complete test suite:

```bash
./mvnw test
```

Integration tests use Testcontainers for MySQL, Redis, and RabbitMQ and require Docker to be available. The 100-delivery rate-limit load test is opt-in because it takes several minutes:

```bash
./mvnw -Drun.rate.limit.load.test=true -Dtest=RateLimitLoadIntegrationTest test
```

The suite includes unit tests, real-MySQL repository tests, and API/worker/recovery integration tests. Postman collections for staged manual verification may be present in `postman_tests/`; that directory is gitignored in this workspace. Set collection/environment variables (API URL, credentials, RabbitMQ management credentials, and a real webhook.site inbox URL where used) before running those collections.

## Troubleshooting

**The app container is unhealthy or does not start**

```bash
docker compose ps
docker compose logs -f app
docker compose logs mysql redis rabbitmq
```

Check that dependency health checks pass, the host ports are free, and `.env` contains a non-empty `JWT_SECRET`. Flyway or Hibernate validation errors generally indicate an incomplete/incorrect database schema; preserve the Flyway history rather than manually altering migration records.

**A Postman request reports an empty URL or 401**

Check the collection's `baseUrl`, ensure request URLs use it, and run registration/login before protected requests. Use the returned access token (not refresh token) in the bearer authorization field. Re-registering an already-used email/username returns a conflict; use a new test identity or log in.

**A delivery does not arrive**

Confirm the webhook is active, subscribes to the exact event type, and its URL can be reached from the API/container network. Inspect the delivery status and attempt logs through the admin APIs, then check RabbitMQ queue depths and application logs. A URL such as `localhost` from inside Docker refers to the app container itself, not the host machine.

**Reset local Compose data**

`docker compose down -v` removes MySQL, Redis, and RabbitMQ volumes. This is destructive to local test data; use it only when a clean environment is intended.

## Known limitations and future work

- Redis outcome counters and latency samples are best-effort projections and are not automatically rebuilt from MySQL after Redis data loss.
- Rate limiting uses multiple Redis commands rather than one Lua-atomic operation, so concurrent workers may race near the configured limit.
- The database and RabbitMQ publish are not coordinated by a transactional outbox. Scheduled reconciliation/redispatch repairs several orphan cases, but an outbox would provide stronger publication guarantees.
- There is no frontend dashboard, Prometheus/Grafana integration, or alerting yet.
- Health scoring uses accumulated counters rather than a rolling window, so recovery can appear gradual.
- JWT blacklist records are stored in MySQL; Redis could reduce lookup overhead at higher scale.

Potential follow-up work includes a transactional outbox, Lua-based atomic rate limiting, rebuildable/rolling monitoring projections, metrics and alerting, a dashboard UI, and API-key authentication for service-to-service event publishers.
