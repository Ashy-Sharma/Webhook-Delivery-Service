package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.Role;
import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.DeliveryFailureReason;
import com.projects.webhookdeliveryservice.entity.Event;
import com.projects.webhookdeliveryservice.entity.DeliveryLog;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.DeliveryLogRepository;
import com.projects.webhookdeliveryservice.service.DeliveryLogService;
import com.projects.webhookdeliveryservice.service.DeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
class WebhookRepositoryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryLogRepository deliveryLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    private Long singleMatchWebhookId;
    private Long orderOnlyWebhookId;
    private Long multiEventWebhookId;

    @BeforeEach
    void seedWebhooks() {
        User owner = User.builder()
                .email("owner-" + UUID.randomUUID() + "@example.com")
                .userName("owner-" + UUID.randomUUID())
                .passwordHash("not-used-in-this-test")
                .role(Role.USER)
                .build();
        entityManager.persist(owner);

        Webhook singleMatchWebhook = webhook(owner, "[\"invoice.paid\"]", true);
        Webhook orderOnlyWebhook = webhook(owner, "[\"order.created\"]", true);
        Webhook multiEventWebhook = webhook(owner, "[\"order.created\",\"user.signup\"]", true);
        webhook(singleMatchWebhook);
        webhook(orderOnlyWebhook);
        webhook(multiEventWebhook);
        entityManager.flush();

        singleMatchWebhookId = singleMatchWebhook.getId();
        orderOnlyWebhookId = orderOnlyWebhook.getId();
        multiEventWebhookId = multiEventWebhook.getId();
    }

    @Test
    void findsSingleWebhookMatchingEventType() {
        List<Webhook> matches = webhookRepository.findActiveWebhooksByEventType("invoice.paid");

        assertThat(matches).extracting(Webhook::getId).containsExactly(singleMatchWebhookId);
    }

    @Test
    void historyQueriesBatchLatestLogAndAverageOnlyRecentSuccessfulHttpAttempts() {
        Event event = Event.builder().eventType("order.created").payload("{}").source("monitoring-test").build();
        entityManager.persist(event);
        Webhook webhook = entityManager.getReference(Webhook.class, orderOnlyWebhookId);
        Delivery delivered = Delivery.builder().event(event).webhook(webhook)
                .status(DeliveryStatus.DELIVERED).attemptCount(2).maxAttempts(5).build();
        entityManager.persist(delivered);
        entityManager.flush();

        entityManager.persist(DeliveryLog.builder().delivery(delivered).attemptNumber(1)
                .responseStatus(503).responseTimeMs(10_000L).errorMessage("server error").build());
        entityManager.persist(DeliveryLog.builder().delivery(delivered).attemptNumber(2)
                .responseStatus(204).responseTimeMs(200L).build());
        entityManager.flush();

        List<DeliveryLogRepository.LatestDeliveryLogProjection> latest =
                deliveryLogRepository.findLatestLogsForDeliveries(List.of(delivered.getId()));
        Double averageSuccessLatency = deliveryRepository.averageSuccessfulLatencySince(Instant.now().minusSeconds(60));
        List<DeliveryLogRepository.WebhookLastDeliveryProjection> lastDeliveryTimes =
                deliveryLogRepository.findLastDeliveryTimesForWebhooks(List.of(orderOnlyWebhookId));
        var page = deliveryRepository.findByWebhook_IdAndStatus(orderOnlyWebhookId, DeliveryStatus.DELIVERED,
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"))));

        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).getAttemptNumber()).isEqualTo(2);
        assertThat(latest.get(0).getResponseStatus()).isEqualTo(204);
        assertThat(averageSuccessLatency).isEqualTo(200.0);
        assertThat(lastDeliveryTimes).hasSize(1);
        assertThat(lastDeliveryTimes.get(0).getWebhookId()).isEqualTo(orderOnlyWebhookId);
        assertThat(lastDeliveryTimes.get(0).getLastDeliveryAt()).isNotNull();
        assertThat(webhookRepository.findAllActiveNotDeleted())
                .extracting(Webhook::getId).contains(orderOnlyWebhookId);
        assertThat(page.getContent()).extracting(Delivery::getId).contains(delivered.getId());
        assertThat(page.getContent().get(0).getEvent().getEventType()).isEqualTo("order.created");
    }

    @Test
    void findsMultipleWebhooksMatchingEventType() {
        List<Webhook> matches = webhookRepository.findActiveWebhooksByEventType("order.created");

        assertThat(matches).extracting(Webhook::getId)
                .containsExactlyInAnyOrder(orderOnlyWebhookId, multiEventWebhookId);
    }

    @Test
    void excludesInactiveMatchingWebhooks() {
        User owner = entityManager.createQuery("select u from User u", User.class).getSingleResult();
        Webhook inactiveWebhook = webhook(owner, "[\"payment.failed\"]", false);
        entityManager.persist(inactiveWebhook);
        entityManager.flush();

        assertThat(webhookRepository.findActiveWebhooksByEventType("payment.failed")).isEmpty();
    }

    @Test
    void returnsNoResultsWhenThereIsNoMatch() {
        assertThat(webhookRepository.findActiveWebhooksByEventType("payment.failed")).isEmpty();
    }

    @Test
    void throttleClaimReleaseRequiresCurrentFenceAndRefundsOneAttempt() {
        Instant claimedAt = Instant.parse("2026-10-08T12:00:00Z");
        Instant nextRetryAt = claimedAt.plusSeconds(10);
        Instant throttleDeadline = Instant.now().minusSeconds(3600);
        Event event = Event.builder()
                .eventType("order.created")
                .payload("{}")
                .source("integration-test")
                .build();
        entityManager.persist(event);

        Delivery delivery = Delivery.builder()
                .event(event)
                .webhook(entityManager.getReference(Webhook.class, orderOnlyWebhookId))
                .status(DeliveryStatus.DELIVERING)
                .attemptCount(3)
                .maxAttempts(5)
                .throttleCount(0)
                .claimedAt(claimedAt)
                .build();
        entityManager.persist(delivery);
        entityManager.flush();

        int wrongFence = deliveryRepository.releaseClaimForThrottle(delivery.getId(),
                DeliveryStatus.DELIVERING, claimedAt.plusSeconds(1), DeliveryStatus.RETRYING,
                nextRetryAt, Instant.now(), throttleDeadline);
        assertThat(wrongFence).isZero();

        entityManager.clear();
        Delivery unchanged = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(DeliveryStatus.DELIVERING);
        assertThat(unchanged.getAttemptCount()).isEqualTo(3);
        assertThat(unchanged.getClaimedAt()).isEqualTo(claimedAt);

        int matchingFence = deliveryRepository.releaseClaimForThrottle(delivery.getId(),
                DeliveryStatus.DELIVERING, claimedAt, DeliveryStatus.RETRYING,
                nextRetryAt, Instant.now(), throttleDeadline);
        assertThat(matchingFence).isEqualTo(1);

        entityManager.clear();
        Delivery released = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(released.getAttemptCount()).isEqualTo(2);
        assertThat(released.getThrottleCount()).isEqualTo(1);
        assertThat(released.getClaimedAt()).isNull();
        assertThat(released.getNextRetryAt()).isEqualTo(nextRetryAt);
        assertThat(released.getThrottledSince()).isNotNull();

        int secondRelease = deliveryRepository.releaseClaimForThrottle(delivery.getId(),
                DeliveryStatus.DELIVERING, claimedAt, DeliveryStatus.RETRYING,
                nextRetryAt, Instant.now(), throttleDeadline);
        assertThat(secondRelease).isZero();
    }

    @Test
    void crashExhaustionClaimIsAtomicAndTerminalAuditUsesItsOwnFailureReason() {
        Event event = Event.builder().eventType("order.created").payload("{}").source("integration-test").build();
        entityManager.persist(event);
        Webhook webhook = entityManager.getReference(Webhook.class, orderOnlyWebhookId);
        Delivery delivery = Delivery.builder()
                .event(event).webhook(webhook).status(DeliveryStatus.PENDING)
                .attemptCount(5).maxAttempts(5).build();
        entityManager.persist(delivery);
        entityManager.flush();

        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int wrongWebhook = deliveryRepository.claimExhaustedDeliveryForDlq(delivery.getId(),
                singleMatchWebhookId, DeliveryStatus.DELIVERING,
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), claimedAt);
        assertThat(wrongWebhook).isZero();

        int claimed = deliveryRepository.claimExhaustedDeliveryForDlq(delivery.getId(), orderOnlyWebhookId,
                DeliveryStatus.DELIVERING, List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), claimedAt);
        assertThat(claimed).isEqualTo(1);
        assertThat(deliveryRepository.claimExhaustedDeliveryForDlq(delivery.getId(), orderOnlyWebhookId,
                DeliveryStatus.DELIVERING, List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING),
                claimedAt.plusSeconds(1))).isZero();

        entityManager.clear();
        Delivery claimedDelivery = deliveryRepository.findDetailedById(delivery.getId()).orElseThrow();
        assertThat(claimedDelivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERING);
        assertThat(claimedDelivery.getAttemptCount()).isEqualTo(5);
        DeliveryLogService logService = new DeliveryLogService(deliveryLogRepository, new ObjectMapper());
        DeliveryService service = new DeliveryService(deliveryRepository, webhookRepository, logService);
        DeliveryService.DeliveryAttempt attempt = new DeliveryService.DeliveryAttempt(claimedDelivery,
                claimedDelivery.getWebhook(), 5, claimedDelivery.getClaimedAt());

        assertThat(service.failCrashExhaustedDelivery(attempt)).isTrue();
        entityManager.flush();
        entityManager.clear();

        Delivery terminal = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(terminal.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(terminal.getFailureReason()).isEqualTo(DeliveryFailureReason.WORKER_CRASH_EXHAUSTED);
        assertThat(terminal.getAttemptCount()).isEqualTo(5);
        DeliveryLog audit = deliveryLogRepository.findAll().stream()
                .filter(row -> row.getDelivery().getId().equals(delivery.getId())).findFirst().orElseThrow();
        assertThat(audit.getAttemptNumber()).isZero();
        assertThat(audit.getResponseStatus()).isNull();
        assertThat(audit.getErrorMessage()).contains("final subscriber request outcome is unknown");
    }

    @Test
    void throttleAgeLimitTerminalizesOldDeliveryAndStillRefundsTheUnsentAttempt() {
        Instant claimedAt = Instant.parse("2026-10-08T12:00:00Z");
        Event event = Event.builder()
                .eventType("order.created")
                .payload("{}")
                .source("integration-test")
                .build();
        entityManager.persist(event);

        Delivery delivery = Delivery.builder()
                .event(event)
                .webhook(entityManager.getReference(Webhook.class, orderOnlyWebhookId))
                .status(DeliveryStatus.DELIVERING)
                .attemptCount(3)
                .maxAttempts(5)
                .throttleCount(1)
                .throttledSince(Instant.now().minusSeconds(3601))
                .claimedAt(claimedAt)
                .build();
        entityManager.persist(delivery);
        entityManager.flush();

        Instant throttleDeadline = Instant.now().minusSeconds(3600);

        int updated = deliveryRepository.failDeliveryAfterThrottleAge(delivery.getId(),
                DeliveryStatus.DELIVERING, claimedAt, DeliveryStatus.FAILED,
                DeliveryFailureReason.THROTTLE_AGE_EXCEEDED, Instant.now(), throttleDeadline);
        assertThat(updated).isEqualTo(1);

        entityManager.clear();
        Delivery failed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo(DeliveryFailureReason.THROTTLE_AGE_EXCEEDED);
        assertThat(failed.getAttemptCount()).isEqualTo(2);
        assertThat(failed.getThrottleCount()).isEqualTo(2);
        assertThat(failed.getClaimedAt()).isNull();
        assertThat(failed.getNextRetryAt()).isNull();
        assertThat(failed.getThrottledSince()).isNull();
    }

    @Test
    void ageLimitPathWritesDistinctAuditLogInMysql() {
        Instant claimedAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        Instant throttleDeadline = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        Instant throttledSince = throttleDeadline.minusSeconds(1);
        Event event = Event.builder()
                .eventType("order.created")
                .payload("{}")
                .source("integration-test")
                .build();
        entityManager.persist(event);
        Webhook webhook = entityManager.getReference(Webhook.class, orderOnlyWebhookId);
        Delivery delivery = Delivery.builder()
                .event(event)
                .webhook(webhook)
                .status(DeliveryStatus.DELIVERING)
                .attemptCount(2)
                .maxAttempts(5)
                .claimedAt(claimedAt)
                .throttledSince(throttledSince)
                .build();
        entityManager.persist(delivery);
        entityManager.flush();

        DeliveryLogService logService = new DeliveryLogService(deliveryLogRepository, new ObjectMapper());
        DeliveryService service = new DeliveryService(deliveryRepository, webhookRepository, logService);
        DeliveryService.DeliveryAttempt attempt = new DeliveryService.DeliveryAttempt(
                delivery, webhook, 2, claimedAt);
        DeliveryService.ThrottleReleaseResult result = service.releaseClaimForThrottle(
                attempt, Instant.now().plusSeconds(2), throttleDeadline, 3_600_000);
        entityManager.flush();
        entityManager.clear();

        assertThat(result).isEqualTo(DeliveryService.ThrottleReleaseResult.LIMIT_REACHED);
        Delivery failed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo(DeliveryFailureReason.THROTTLE_AGE_EXCEEDED);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        List<DeliveryLog> logs = deliveryLogRepository.findAll();
        DeliveryLog audit = logs.stream()
                .filter(row -> row.getDelivery().getId().equals(delivery.getId()))
                .findFirst().orElseThrow();
        assertThat(audit.getAttemptNumber()).isZero();
        assertThat(audit.getResponseStatus()).isZero();
        assertThat(audit.getErrorMessage()).contains("no HTTP request was sent");
    }

    @Test
    void concurrentClaimsOnMysqlHaveExactlyOneWinner() throws Exception {
        Event event = Event.builder()
                .eventType("order.created")
                .payload("{}")
                .source("integration-test")
                .build();
        entityManager.persist(event);
        Delivery delivery = Delivery.builder()
                .event(event)
                .webhook(entityManager.getReference(Webhook.class, orderOnlyWebhookId))
                .status(DeliveryStatus.PENDING)
                .attemptCount(0)
                .maxAttempts(5)
                .build();
        entityManager.persist(delivery);
        entityManager.flush();
        Long deliveryId = delivery.getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(() -> claimAfterBarrier(
                    transactions, deliveryId, ready, start));
            Future<Integer> second = workers.submit(() -> claimAfterBarrier(
                    transactions, deliveryId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int totalClaims = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);

            assertThat(totalClaims).isEqualTo(1);
            Delivery claimed = deliveryRepository.findById(deliveryId).orElseThrow();
            assertThat(claimed.getStatus()).isEqualTo(DeliveryStatus.DELIVERING);
            assertThat(claimed.getAttemptCount()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void retryRedispatchReservationDoesNotPushTheDeliveryDeadlineIntoTheFuture() {
        Event event = Event.builder()
                .eventType("order.created")
                .payload("{}")
                .source("integration-test")
                .build();
        entityManager.persist(event);
        Delivery delivery = Delivery.builder()
                .event(event)
                .webhook(entityManager.getReference(Webhook.class, orderOnlyWebhookId))
                .status(DeliveryStatus.RETRYING)
                .attemptCount(1)
                .maxAttempts(5)
                .nextRetryAt(Instant.now().minusSeconds(120))
                .build();
        entityManager.persist(delivery);
        entityManager.flush();

        Instant now = Instant.now();
        Instant threshold = now.minusSeconds(30);
        Instant oldUpdatedAt = now.minusSeconds(120);
        entityManager.createNativeQuery("UPDATE deliveries SET updated_at = :updatedAt WHERE id = :id")
                .setParameter("updatedAt", oldUpdatedAt)
                .setParameter("id", delivery.getId())
                .executeUpdate();

        int reserved = deliveryRepository.rescheduleRetryDispatch(
                delivery.getId(), DeliveryStatus.RETRYING, threshold, now);
        entityManager.clear();
        Delivery reservedDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        int secondReservation = deliveryRepository.rescheduleRetryDispatch(
                delivery.getId(), DeliveryStatus.RETRYING, threshold, now.plusSeconds(1));

        assertThat(reserved).isEqualTo(1);
        assertThat(reservedDelivery.getNextRetryAt()).isBefore(threshold);
        assertThat(reservedDelivery.getUpdatedAt()).isAfter(oldUpdatedAt);
        assertThat(secondReservation).isZero();
    }

    @Test
    void releasedExpiredClaimIsImmediatelyClaimableAfterMainQueueRepublish() {
        Instant now = Instant.now();
        Instant threshold = now.minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        Event event = Event.builder()
                .eventType("order.created")
                .payload("{}")
                .source("integration-test")
                .build();
        entityManager.persist(event);
        Delivery delivery = Delivery.builder()
                .event(event)
                .webhook(entityManager.getReference(Webhook.class, orderOnlyWebhookId))
                .status(DeliveryStatus.DELIVERING)
                .attemptCount(1)
                .maxAttempts(5)
                .claimedAt(threshold.minusSeconds(1))
                .throttledSince(now.minusSeconds(90))
                .build();
        entityManager.persist(delivery);
        entityManager.flush();

        int released = deliveryRepository.releaseExpiredClaim(delivery.getId(),
                DeliveryStatus.DELIVERING, DeliveryStatus.RETRYING, threshold, now);
        int reclaimed = deliveryRepository.claimForDelivery(delivery.getId(),
                DeliveryStatus.DELIVERING, List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING),
                Instant.now().truncatedTo(ChronoUnit.SECONDS), DeliveryStatus.RETRYING);

        assertThat(released).isEqualTo(1);
        entityManager.clear();
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getThrottledSince()).isNull();
        assertThat(reclaimed).isEqualTo(1);
    }

    @Test
    void scheduleRetryClearsThrottleWindowInTheExistingFencedUpdate() {
        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Event event = Event.builder().eventType("order.created").payload("{}").source("integration-test").build();
        entityManager.persist(event);
        Delivery delivery = Delivery.builder()
                .event(event)
                .webhook(entityManager.getReference(Webhook.class, orderOnlyWebhookId))
                .status(DeliveryStatus.DELIVERING)
                .attemptCount(2)
                .maxAttempts(5)
                .claimedAt(claimedAt)
                .throttledSince(Instant.now().minusSeconds(10))
                .build();
        entityManager.persist(delivery);
        entityManager.flush();

        int updated = deliveryRepository.scheduleRetry(delivery.getId(), DeliveryStatus.DELIVERING,
                claimedAt, DeliveryStatus.RETRYING, Instant.now().plusSeconds(10), Instant.now());

        entityManager.clear();
        Delivery rescheduled = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(rescheduled.getThrottledSince()).isNull();
    }

    @Test
    void manualRetryResetOnlyResetsFailedDeliveriesAndClearsRetryState() {
        Event event = Event.builder().eventType("order.created").payload("{}").source("integration-test").build();
        entityManager.persist(event);
        Delivery failed = Delivery.builder()
                .event(event)
                .webhook(entityManager.getReference(Webhook.class, orderOnlyWebhookId))
                .status(DeliveryStatus.FAILED)
                .failureReason(DeliveryFailureReason.HTTP_EXHAUSTED)
                .attemptCount(5)
                .maxAttempts(5)
                .claimedAt(Instant.now().minusSeconds(60))
                .nextRetryAt(Instant.now().minusSeconds(30))
                .throttledSince(Instant.now().minusSeconds(120))
                .deliveredAt(Instant.now().minusSeconds(60))
                .build();
        Delivery delivered = Delivery.builder()
                .event(event)
                .webhook(entityManager.getReference(Webhook.class, orderOnlyWebhookId))
                .status(DeliveryStatus.DELIVERED)
                .attemptCount(1)
                .maxAttempts(5)
                .deliveredAt(Instant.now())
                .build();
        entityManager.persist(failed);
        entityManager.persist(delivered);
        entityManager.flush();

        int reset = deliveryRepository.resetFailedDeliveryForManualRetry(
                failed.getId(), DeliveryStatus.FAILED, DeliveryStatus.PENDING, Instant.now());
        int deliveredReset = deliveryRepository.resetFailedDeliveryForManualRetry(
                delivered.getId(), DeliveryStatus.FAILED, DeliveryStatus.PENDING, Instant.now());

        entityManager.clear();
        Delivery resetDelivery = deliveryRepository.findById(failed.getId()).orElseThrow();
        assertThat(reset).isEqualTo(1);
        assertThat(deliveredReset).isZero();
        assertThat(resetDelivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(resetDelivery.getAttemptCount()).isZero();
        assertThat(resetDelivery.getFailureReason()).isNull();
        assertThat(resetDelivery.getClaimedAt()).isNull();
        assertThat(resetDelivery.getNextRetryAt()).isNull();
        assertThat(resetDelivery.getThrottledSince()).isNull();
        assertThat(resetDelivery.getDeliveredAt()).isNull();
    }

    @Test
    void completingAttemptClearsThrottleWindowInTheExistingFencedUpdate() {
        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Event event = Event.builder().eventType("order.created").payload("{}").source("integration-test").build();
        entityManager.persist(event);
        Delivery delivery = Delivery.builder()
                .event(event)
                .webhook(entityManager.getReference(Webhook.class, orderOnlyWebhookId))
                .status(DeliveryStatus.DELIVERING)
                .attemptCount(1)
                .maxAttempts(5)
                .claimedAt(claimedAt)
                .throttledSince(Instant.now().minusSeconds(10))
                .build();
        entityManager.persist(delivery);
        entityManager.flush();

        int updated = deliveryRepository.finishClaimedDelivery(delivery.getId(), DeliveryStatus.DELIVERING,
                claimedAt, DeliveryStatus.DELIVERED, null, Instant.now(), Instant.now());

        entityManager.clear();
        Delivery completed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(completed.getThrottledSince()).isNull();
    }

    private int claimAfterBarrier(TransactionTemplate transactions, Long deliveryId,
                                  CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start concurrent claim");
        }
        return transactions.execute(status -> deliveryRepository.claimForDelivery(
                deliveryId, DeliveryStatus.DELIVERING,
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING),
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                DeliveryStatus.RETRYING));
    }

    private Webhook webhook(User owner, String eventTypes, boolean active) {
        return Webhook.builder()
                .owner(owner)
                .url("https://example.com/hook")
                .secretKey("test-secret")
                .eventType(eventTypes)
                .isActive(active)
                .build();
    }

    private void webhook(Webhook webhook) {
        entityManager.persist(webhook);
    }
}
