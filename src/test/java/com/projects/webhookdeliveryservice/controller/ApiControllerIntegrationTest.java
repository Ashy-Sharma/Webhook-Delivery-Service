package com.projects.webhookdeliveryservice.controller;

import com.projects.webhookdeliveryservice.service.ConfirmedRabbitPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
class ApiControllerIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ConfirmedRabbitPublisher rabbitPublisher;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM delivery_logs");
        jdbcTemplate.update("DELETE FROM deliveries");
        jdbcTemplate.update("DELETE FROM events");
        jdbcTemplate.update("DELETE FROM webhooks");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM blacklisted_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void registerReturnsCreatedAndBothTokens() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void loginReturnsTokensForRegisteredUser() throws Exception {
        String email = registerUser();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void protectedWebhookListRejectsRequestsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/webhooks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsAvailableWithoutAuthenticationForContainerHealthcheck() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void createWebhookReturnsCreatedAndGeneratedSecret() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(post("/api/webhooks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWebhookBody("https://example.com/webhooks")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.secretKey").isNotEmpty());
    }

    @Test
    void createWebhookRejectsInvalidUrl() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(post("/api/webhooks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWebhookBody("not-a-url")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhookListContainsOnlyAuthenticatedOwnersWebhooks() throws Exception {
        String firstToken = registerAndGetToken();
        String secondToken = registerAndGetToken();
        createWebhook(firstToken, "https://example.com/first");
        createWebhook(secondToken, "https://example.com/second");

        mockMvc.perform(get("/api/webhooks").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].url").value("https://example.com/first"))
                .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void publishingMatchingEventReturnsCreatedAndSchedulesDeliveryPublish() throws Exception {
        String token = registerAndGetToken();
        createWebhook(token, "https://example.com/events");

        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.created\",\"payload\":{\"orderId\":42}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.webhooksMatched").value(1))
                .andExpect(jsonPath("$.deliveriesCreated").value(1));

        verify(rabbitPublisher).publish(eq("webhook.exchange"), eq("webhook.deliver"), any());
    }

    @Test
    void publishingUnknownEventTypeReturnsBadRequest() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"not.allowed\",\"payload\":{\"id\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Allowed types")));
    }

    private String registerUser() throws Exception {
        String email = "day7-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email)))
                .andExpect(status().isCreated());
        return email;
    }

    private String registerAndGetToken() throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(body -> com.jayway.jsonpath.JsonPath.read(body, "$.accessToken"));
    }

    private void createWebhook(String token, String url) throws Exception {
        mockMvc.perform(post("/api/webhooks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWebhookBody(url)))
                .andExpect(status().isCreated());
    }

    private String registerBody() {
        return registerBody("day7-" + UUID.randomUUID() + "@example.com");
    }

    private String registerBody(String email) {
        String username = "day7_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "{\"email\":\"" + email + "\",\"username\":\"" + username
                + "\",\"password\":\"Passw0rd!123\"}";
    }

    private String loginBody(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!123\"}";
    }

    private String validWebhookBody(String url) {
        return "{\"url\":\"" + url + "\",\"eventTypes\":[\"order.created\"]}";
    }
}
