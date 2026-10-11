package com.projects.webhookdeliveryservice.worker;

import com.projects.webhookdeliveryservice.dto.DeliveryMessage;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryFailureReason;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.Event;
import com.projects.webhookdeliveryservice.entity.Role;
import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.repository.DeliveryLogRepository;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.EventRepository;
import com.projects.webhookdeliveryservice.repository.UserRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import com.projects.webhookdeliveryservice.service.ConfirmedRabbitPublisher;
import com.projects.webhookdeliveryservice.service.DeliveryReconciliationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1"
})
class CrashRecoveryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>(
            DockerImageName.parse("rabbitmq:4.1-management-alpine"))
            .withEnv("RABBITMQ_DEFAULT_USER", "webhook")
            .withEnv("RABBITMQ_DEFAULT_PASS", "webhook")
            .withExposedPorts(5672)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void configureServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "webhook");
        registry.add("spring.rabbitmq.password", () -> "webhook");
    }

    @Autowired private UserRepository userRepository;
    @Autowired private WebhookRepository webhookRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private DeliveryLogRepository deliveryLogRepository;
    @Autowired private DeliveryReconciliationService reconciliationService;
    @Autowired private ConfirmedRabbitPublisher publisher;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void oneExpiredExhaustedClaimRecoveryPublishesOneDlqMessage() throws Exception {
        rabbitAdmin.purgeQueue("webhook.dlq");
        Delivery delivery = createExpiredExhaustedDelivery();

        reconciliationService.recoverExpiredClaims();
        awaitFailed(delivery);

        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getFailureReason())
                .isEqualTo(DeliveryFailureReason.WORKER_CRASH_EXHAUSTED);
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getAttemptCount()).isEqualTo(2);
        Message deadLetter = rabbitTemplate.receive("webhook.dlq", 5_000);
        assertThat(deadLetter).isNotNull();
        assertThat(body(deadLetter)).contains("\"deliveryId\":" + delivery.getId());
        assertThat(rabbitAdmin.getQueueInfo("webhook.dlq").getMessageCount()).isZero();
        assertSingleCrashAudit(delivery);
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void confirmedDlqPublishBeforeCrashCanProduceDuplicateOnLeaseRecovery() throws Exception {
        rabbitAdmin.purgeQueue("webhook.dlq");
        Delivery delivery = createExpiredExhaustedDelivery();
        DeliveryMessage message = messageFor(delivery);

        // Represents the confirmed publish from a worker that died before its FAILED write.
        publisher.publish("webhook.exchange", "webhook.dlq", message);
        assertThat(rabbitAdmin.getQueueInfo("webhook.dlq").getMessageCount()).isEqualTo(1);

        reconciliationService.recoverExpiredClaims();
        awaitFailed(delivery);

        Message first = rabbitTemplate.receive("webhook.dlq", 5_000);
        Message second = rabbitTemplate.receive("webhook.dlq", 5_000);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(body(first)).contains("\"deliveryId\":" + delivery.getId());
        assertThat(body(second)).contains("\"deliveryId\":" + delivery.getId());
        assertThat(rabbitAdmin.getQueueInfo("webhook.dlq").getMessageCount()).isZero();
        assertSingleCrashAudit(delivery);
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void oldInfrastructureDeferralGetsARealAttemptAfterRecovery() throws Exception {
        rabbitAdmin.purgeQueue("webhook.dlq");
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try {
        Delivery delivery = createPendingInfrastructureExpiredDelivery(
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook");
        DeliveryMessage message = messageFor(delivery, Instant.now().minusSeconds(360));

        publisher.publish("webhook.exchange", "webhook.deliver", message);
        awaitDelivered(delivery);

        Delivery delivered = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivered.getFailureReason()).isNull();
        assertThat(delivered.getAttemptCount()).isEqualTo(1);
        assertThat(rabbitAdmin.getQueueInfo("webhook.dlq").getMessageCount()).isZero();
        var audit = deliveryLogRepository.findAll().stream()
                .filter(log -> log.getDelivery().getId().equals(delivery.getId())).toList();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(audit.get(0).getResponseStatus()).isEqualTo(204);
        assertThat(delivered.getInfrastructureDeferredSince()).isNull();
        } finally {
            receiver.stop(0);
        }
    }

    private Delivery createExpiredExhaustedDelivery() {
        String suffix = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email("crash-recovery-" + suffix + "@example.com")
                .userName("crash-recovery-" + suffix)
                .passwordHash("not-used")
                .role(Role.USER)
                .build());
        Webhook webhook = webhookRepository.save(Webhook.builder()
                .owner(owner)
                .url("http://127.0.0.1:1/crash-recovery")
                .secretKey("crash-recovery-secret")
                .eventType("[\"crash.recovery\"]")
                .isActive(true)
                .build());
        Event event = eventRepository.save(Event.builder()
                .eventType("crash.recovery")
                .payload("{\"crash\":true}")
                .source("crash-recovery-test")
                .build());
        Instant staleClaim = Instant.now().minusSeconds(90).truncatedTo(ChronoUnit.SECONDS);
        Delivery delivery = deliveryRepository.save(Delivery.builder()
                .event(event)
                .webhook(webhook)
                .status(DeliveryStatus.DELIVERING)
                .attemptCount(2)
                .maxAttempts(2)
                .claimedAt(staleClaim)
                .build());
        deliveryRepository.flush();
        return delivery;
    }

    private Delivery createPendingInfrastructureExpiredDelivery(String webhookUrl) {
        String suffix = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email("infra-deferral-" + suffix + "@example.com")
                .userName("infra-deferral-" + suffix)
                .passwordHash("not-used")
                .role(Role.USER)
                .build());
        Webhook webhook = webhookRepository.save(Webhook.builder()
                .owner(owner)
                .url(webhookUrl)
                .secretKey("infrastructure-deferral-secret")
                .eventType("[\"infrastructure.deferral\"]")
                .isActive(true)
                .build());
        Event event = eventRepository.save(Event.builder()
                .eventType("infrastructure.deferral")
                .payload("{\"deferred\":true}")
                .source("infrastructure-deferral-test")
                .build());
        Delivery delivery = deliveryRepository.save(Delivery.builder()
                .event(event)
                .webhook(webhook)
                .status(DeliveryStatus.PENDING)
                .attemptCount(0)
                .maxAttempts(5)
                .infrastructureDeferredSince(Instant.now().minusSeconds(360))
                .build());
        deliveryRepository.flush();
        return delivery;
    }

    private void awaitFailed(Delivery delivery) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus() == DeliveryStatus.FAILED) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.FAILED);
    }

    private void awaitDelivered(Delivery delivery) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus() == DeliveryStatus.DELIVERED) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.DELIVERED);
    }

    private void assertSingleCrashAudit(Delivery delivery) {
        var rows = deliveryLogRepository.findAll().stream()
                .filter(log -> log.getDelivery().getId().equals(delivery.getId())).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAttemptNumber()).isZero();
        assertThat(rows.get(0).getResponseStatus()).isNull();
        assertThat(rows.get(0).getResponseTimeMs()).isNull();
        assertThat(rows.get(0).getErrorMessage()).contains("final subscriber request outcome is unknown");
    }

    private DeliveryMessage messageFor(Delivery delivery) {
        return messageFor(delivery, delivery.getInfrastructureDeferredSince());
    }

    private DeliveryMessage messageFor(Delivery delivery, Instant infrastructureDeferredSince) {
        return DeliveryMessage.builder()
                .deliveryId(delivery.getId())
                .webhookId(delivery.getWebhook().getId())
                .eventType(delivery.getEvent().getEventType())
                .payload(delivery.getEvent().getPayload())
                .attemptNumber(2)
                .infrastructureDeferredSince(infrastructureDeferredSince)
                .build();
    }

    private String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }
}
