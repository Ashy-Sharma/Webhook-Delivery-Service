package com.projects.webhookdeliveryservice.worker;

import com.projects.webhookdeliveryservice.dto.DeliveryMessage;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.Event;
import com.projects.webhookdeliveryservice.entity.Role;
import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.EventRepository;
import com.projects.webhookdeliveryservice.repository.UserRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import com.projects.webhookdeliveryservice.service.ConfirmedRabbitPublisher;
import com.projects.webhookdeliveryservice.repository.DeliveryLogRepository;
import com.sun.net.httpserver.HttpServer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "run.rate.limit.load.test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1"
})
class RateLimitLoadIntegrationTest {

    private static final int EVENT_COUNT = 100;

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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryLogRepository deliveryLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfirmedRabbitPublisher publisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Test
    @Timeout(value = 360, unit = TimeUnit.SECONDS)
    void measuresEndToEndRateLimitedBacklogDrainFor100Deliveries() throws Exception {
        List<Instant> arrivals = new CopyOnWriteArrayList<>();
        HttpServer subscriber = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        subscriber.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            arrivals.add(Instant.now());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        subscriber.start();

        try {
            String suffix = UUID.randomUUID().toString();
            User owner = userRepository.save(User.builder()
                    .email("load-" + suffix + "@example.com")
                    .userName("load-" + suffix)
                    .passwordHash("not-used")
                    .role(Role.USER)
                    .build());
            Webhook webhook = webhookRepository.save(Webhook.builder()
                    .owner(owner)
                    .url("http://127.0.0.1:" + subscriber.getAddress().getPort() + "/hook")
                    .secretKey("load-test-secret")
                    .eventType("[\"load.test\"]")
                    .isActive(true)
                    .build());
            Event event = eventRepository.save(Event.builder()
                    .eventType("load.test")
                    .payload("{\"load\":true}")
                    .source("rate-limit-load-test")
                    .build());

            List<Delivery> deliveries = new ArrayList<>(EVENT_COUNT);
            for (int index = 0; index < EVENT_COUNT; index++) {
                deliveries.add(Delivery.builder().webhook(webhook).event(event)
                        .status(DeliveryStatus.PENDING).attemptCount(0).maxAttempts(5).build());
            }
            deliveries = deliveryRepository.saveAll(deliveries);
            deliveryRepository.flush();

            long startNanos = System.nanoTime();
            for (Delivery delivery : deliveries) {
                publisher.publish("webhook.exchange", "webhook.deliver", DeliveryMessage.builder()
                        .deliveryId(delivery.getId())
                        .webhookId(webhook.getId())
                        .eventType("load.test")
                        .payload("{\"load\":true}")
                        .attemptNumber(0)
                        .build());
            }

            long deadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(330);
            while (deliveryRepository.countByStatus(DeliveryStatus.DELIVERED) < EVENT_COUNT
                    && System.nanoTime() < deadlineNanos) {
                Thread.sleep(500);
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            long delivered = deliveryRepository.countByStatus(DeliveryStatus.DELIVERED);
            double deliveriesPerSecond = delivered * 1_000.0 / Math.max(1, elapsedMillis);
            System.out.printf("RATE_LIMIT_LOAD_RESULT events=%d delivered=%d elapsed_ms=%d rate_per_second=%.3f%n",
                    EVENT_COUNT, delivered, elapsedMillis, deliveriesPerSecond);

            assertThat(delivered).isEqualTo(EVENT_COUNT);
            assertThat(arrivals).hasSize(EVENT_COUNT);

            List<Instant> orderedArrivals = arrivals.stream().sorted().toList();
            for (int start = 0; start < orderedArrivals.size(); start++) {
                Instant windowStart = orderedArrivals.get(start);
                long inWindow = orderedArrivals.subList(start, orderedArrivals.size()).stream()
                        .takeWhile(arrival -> Duration.between(windowStart, arrival).toMillis() < 59_500)
                        .count();
                assertThat(inWindow).as("requests in every 59.5-second receiver window").isLessThanOrEqualTo(30);
            }

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM deliveries WHERE attempt_count <> 1 OR status <> 'DELIVERED'",
                    Long.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM delivery_logs WHERE attempt_number = 0", Long.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(throttle_count), 0) FROM deliveries", Long.class)).isGreaterThan(0);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM deliveries WHERE throttled_since IS NOT NULL", Long.class)).isZero();
            assertThat(deliveryLogRepository.findAll()).hasSize(EVENT_COUNT);
        } finally {
            subscriber.stop(0);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void malformedMessageLandsInPoisonQueue() {
        rabbitAdmin.purgeQueue("webhook.deliveries");
        rabbitAdmin.purgeQueue("webhook.poison");
        Message malformed = MessageBuilder.withBody("{not json".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setContentType("application/json")
                .build();

        rabbitTemplate.send("webhook.exchange", "webhook.deliver", malformed);
        Message poisoned = rabbitTemplate.receive("webhook.poison", 10_000);

        assertThat(poisoned).isNotNull();
        assertThat(rabbitAdmin.getQueueInfo("webhook.deliveries").getMessageCount()).isZero();
    }

}
