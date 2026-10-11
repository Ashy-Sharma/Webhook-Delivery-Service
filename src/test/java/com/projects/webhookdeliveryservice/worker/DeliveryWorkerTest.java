package com.projects.webhookdeliveryservice.worker;

import com.projects.webhookdeliveryservice.dto.DeliveryMessage;
import com.projects.webhookdeliveryservice.dto.DeliveryResult;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.exception.UndeliverableDeliveryException;
import com.projects.webhookdeliveryservice.service.DeliveryLogService;
import com.projects.webhookdeliveryservice.service.DeliveryService;
import com.projects.webhookdeliveryservice.service.DeliveryStatsService;
import com.projects.webhookdeliveryservice.service.HmacSignatureService;
import com.projects.webhookdeliveryservice.service.RateLimitService;
import com.projects.webhookdeliveryservice.service.RetryService;
import com.rabbitmq.client.Channel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliveryWorkerTest {

    private static final Long DELIVERY_ID = 42L;
    private static final Long WEBHOOK_ID = 7L;
    private static final String SECRET = "test-webhook-secret";
    private static final String PAYLOAD = "{\"orderId\":42}";
    private static final Instant CLAIMED_AT = Instant.parse("2026-10-08T12:00:00Z");

    private HttpServer server;
    private final AtomicInteger responseStatus = new AtomicInteger(204);
    private final AtomicInteger responseBodySize = new AtomicInteger("subscriber response".length());
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> signatureHeader = new AtomicReference<>();
    private DeliveryService deliveryService;
    private DeliveryLogService deliveryLogService;
    private DeliveryStatsService deliveryStatsService;
    private HmacSignatureService hmacSignatureService;
    private RateLimitService rateLimitService;
    private RetryService retryService;
    private DeliveryWorker deliveryWorker;
    private Delivery delivery;
    private Webhook webhook;
    private String webhookUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            requestCount.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signatureHeader.set(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            byte[] response = "x".repeat(responseBodySize.get()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        webhookUrl = "http://localhost:" + server.getAddress().getPort() + "/hook";

        deliveryService = mock(DeliveryService.class);
        deliveryLogService = mock(DeliveryLogService.class);
        deliveryStatsService = mock(DeliveryStatsService.class);
        hmacSignatureService = new HmacSignatureService();
        rateLimitService = mock(RateLimitService.class);
        retryService = mock(RetryService.class);
        when(rateLimitService.checkAndRecord(WEBHOOK_ID))
                .thenReturn(new RateLimitService.RateLimitDecision(true, 0, 29));
        when(retryService.handleFailure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(RetryService.RetryOutcome.RETRY_SCHEDULED);
        when(deliveryService.completeAttempt(DELIVERY_ID, CLAIMED_AT, true, null)).thenReturn(true);
        deliveryWorker = new DeliveryWorker(
                deliveryService,
                deliveryLogService,
                deliveryStatsService,
                hmacSignatureService,
                rateLimitService,
                retryService,
                WebClient.builder().build());

        delivery = Delivery.builder().id(DELIVERY_ID).build();
        webhook = Webhook.builder()
                .id(WEBHOOK_ID)
                .url(webhookUrl)
                .secretKey(SECRET)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsSignedRequestLogsResponseAndMarksDeliverySuccessful() throws IOException {
        responseStatus.set(204);
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(new DeliveryService.DeliveryAttempt(delivery, webhook, 1, CLAIMED_AT)));
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 11L);

        assertEquals(1, requestCount.get());
        assertEquals(PAYLOAD, requestBody.get());
        assertNotNull(signatureHeader.get());
        assertTrue(signatureHeader.get().startsWith("t="));
        assertTrue(signatureHeader.get().contains(",v1="));
        assertValidSignature(signatureHeader.get());

        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<DeliveryResult> resultCaptor = ArgumentCaptor.forClass(DeliveryResult.class);
        verify(deliveryLogService).logDeliveryAttempt(
                same(delivery), eq(1), eq(webhookUrl), headersCaptor.capture(), eq(PAYLOAD), resultCaptor.capture());
        assertEquals("application/json", headersCaptor.getValue().get("Content-Type"));
        assertEquals("order.created", headersCaptor.getValue().get("X-Webhook-Event"));
        assertEquals("42", headersCaptor.getValue().get("X-Webhook-Delivery-Id"));
        assertEquals(204, resultCaptor.getValue().statusCode());
        assertTrue(resultCaptor.getValue().success());

        verify(deliveryService).completeAttempt(DELIVERY_ID, CLAIMED_AT, true, null);
        verify(deliveryStatsService).recordSuccess(eq(WEBHOOK_ID), org.mockito.ArgumentMatchers.anyLong());
        verify(channel).basicAck(11L, false);
    }

    @Test
    void throttledDeliveryIsRequeuedWithoutSendingHttpRequest() throws IOException {
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(new DeliveryService.DeliveryAttempt(delivery, webhook, 1, CLAIMED_AT)));
        when(rateLimitService.checkAndRecord(WEBHOOK_ID))
                .thenReturn(new RateLimitService.RateLimitDecision(false, 4_000, 0));
        when(retryService.handleThrottled(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), eq(4_000L)))
                .thenReturn(RetryService.RetryOutcome.THROTTLE_REQUEUED);
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 10L);

        assertEquals(0, requestCount.get());
        verify(retryService).handleThrottled(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), eq(4_000L));
        verify(channel).basicAck(10L, false);
        verifyNoInteractions(deliveryLogService);
        verifyNoInteractions(deliveryStatsService);
    }

    @Test
    void recordsNon2xxResponseAsFailedDeliveryAndAcknowledgesMessage() throws IOException {
        responseStatus.set(500);
        responseBodySize.set("subscriber response".length());
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(new DeliveryService.DeliveryAttempt(delivery, webhook, 1, CLAIMED_AT)));
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 12L);

        ArgumentCaptor<DeliveryResult> resultCaptor = ArgumentCaptor.forClass(DeliveryResult.class);
        verify(deliveryLogService).logDeliveryAttempt(
                same(delivery), eq(1), eq(webhookUrl), org.mockito.ArgumentMatchers.anyMap(),
                eq(PAYLOAD), resultCaptor.capture());
        assertEquals(500, resultCaptor.getValue().statusCode());
        assertEquals("x".repeat("subscriber response".length()), resultCaptor.getValue().responseBody());
        assertFalse(resultCaptor.getValue().success());
        assertEquals(null, resultCaptor.getValue().errorMessage());

        verify(retryService).handleFailure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(deliveryStatsService, never()).recordFailure(WEBHOOK_ID);
        verify(deliveryService, never()).completeAttempt(
                org.mockito.ArgumentMatchers.eq(DELIVERY_ID),
                org.mockito.ArgumentMatchers.eq(CLAIMED_AT),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.any());
        verify(channel).basicAck(12L, false);
    }

    @Test
    void recordsFailureStatisticsOnlyAfterHttpRetriesAreExhausted() throws IOException {
        responseStatus.set(500);
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(new DeliveryService.DeliveryAttempt(delivery, webhook, 5, CLAIMED_AT)));
        when(retryService.handleFailure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(RetryService.RetryOutcome.DEAD_LETTERED);
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 21L);

        verify(deliveryStatsService).recordFailure(WEBHOOK_ID);
        verify(channel).basicAck(21L, false);
    }

    @Test
    void largeResponseBodyIsCappedWithoutFailingSuccessfulDelivery() throws IOException {
        responseStatus.set(200);
        responseBodySize.set(1024 * 1024);
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(new DeliveryService.DeliveryAttempt(delivery, webhook, 1, CLAIMED_AT)));
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 14L);

        ArgumentCaptor<DeliveryResult> resultCaptor = ArgumentCaptor.forClass(DeliveryResult.class);
        verify(deliveryLogService).logDeliveryAttempt(
                same(delivery), eq(1), eq(webhookUrl), org.mockito.ArgumentMatchers.anyMap(),
                eq(PAYLOAD), resultCaptor.capture());
        assertEquals(200, resultCaptor.getValue().statusCode());
        assertTrue(resultCaptor.getValue().success());
        assertTrue(resultCaptor.getValue().responseBody().length() <= 4 * 1024);
        verify(deliveryService).completeAttempt(DELIVERY_ID, CLAIMED_AT, true, null);
        verify(channel).basicAck(14L, false);
    }

    @Test
    void recordsTypedErrorWhenSubscriberConnectionFails() throws IOException {
        webhook.setUrl("http://127.0.0.1:1/hook");
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(new DeliveryService.DeliveryAttempt(delivery, webhook, 1, CLAIMED_AT)));
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 20L);

        ArgumentCaptor<DeliveryResult> resultCaptor = ArgumentCaptor.forClass(DeliveryResult.class);
        verify(deliveryLogService).logDeliveryAttempt(
                same(delivery), eq(1), eq("http://127.0.0.1:1/hook"),
                org.mockito.ArgumentMatchers.anyMap(), eq(PAYLOAD), resultCaptor.capture());
        assertFalse(resultCaptor.getValue().success());
        assertNotNull(resultCaptor.getValue().errorMessage());
        assertTrue(resultCaptor.getValue().errorMessage().contains(": "));
        verify(channel).basicAck(20L, false);
    }

    @Test
    void skipsUnclaimableDuplicateWithoutSendingHttpRequest() throws IOException {
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID)).thenReturn(Optional.empty());
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 13L);

        assertEquals(0, requestCount.get());
        verifyNoInteractions(deliveryLogService);
        verify(deliveryService, never()).completeAttempt(DELIVERY_ID, CLAIMED_AT, true, null);
        verify(channel).basicAck(13L, false);
    }

    @Test
    void publishesCrashExhaustedDeliveryBeforeAcknowledging() throws IOException {
        DeliveryService.DeliveryAttempt exhaustedAttempt =
                new DeliveryService.DeliveryAttempt(delivery, webhook, 5, CLAIMED_AT);
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID)).thenReturn(Optional.empty());
        when(deliveryService.claimExhaustedDeliveryForDlq(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(exhaustedAttempt));
        when(retryService.handleCrashExhaustion(org.mockito.ArgumentMatchers.same(exhaustedAttempt),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(RetryService.RetryOutcome.CRASH_EXHAUSTED_DEAD_LETTERED);
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 22L);

        assertEquals(0, requestCount.get());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(retryService, channel);
        order.verify(retryService).handleCrashExhaustion(org.mockito.ArgumentMatchers.same(exhaustedAttempt),
                org.mockito.ArgumentMatchers.any());
        order.verify(channel).basicAck(22L, false);
        verifyNoInteractions(deliveryStatsService);
    }

    @Test
    void defersCrashExhaustionWhenDlqHandlingFails() throws IOException {
        DeliveryService.DeliveryAttempt exhaustedAttempt =
                new DeliveryService.DeliveryAttempt(delivery, webhook, 5, CLAIMED_AT);
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID)).thenReturn(Optional.empty());
        when(deliveryService.claimExhaustedDeliveryForDlq(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(exhaustedAttempt));
        doThrow(new IllegalStateException("publisher confirm failed"))
                .when(retryService).handleCrashExhaustion(org.mockito.ArgumentMatchers.same(exhaustedAttempt),
                        org.mockito.ArgumentMatchers.any());
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 23L);

        verify(retryService).deferInfrastructureFailure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.same(exhaustedAttempt));
        verify(channel).basicAck(23L, false);
        verify(channel, never()).basicNack(23L, false, true);
        verifyNoInteractions(deliveryStatsService);
    }

    @Test
    void defersWhenClaimCannotBePersisted() throws IOException {
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenThrow(new IllegalStateException("database unavailable"));
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 15L);

        verify(retryService).deferInfrastructureFailure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull());
        verify(channel).basicAck(15L, false);
        verify(channel, never()).basicNack(15L, false, true);
        verifyNoInteractions(deliveryLogService);
    }

    @Test
    void defersCompletionWriteFailureWithoutMarkingKnownHttpResultFailed() throws IOException {
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(new DeliveryService.DeliveryAttempt(delivery, webhook, 1, CLAIMED_AT)));
        doThrow(new IllegalStateException("database unavailable"))
                .when(deliveryService).completeAttempt(DELIVERY_ID, CLAIMED_AT, true, null);
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 16L);

        verify(retryService).deferInfrastructureFailure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(DeliveryService.DeliveryAttempt.class));
        verify(channel).basicAck(16L, false);
        verify(channel, never()).basicNack(16L, false, true);
        verify(deliveryService, never()).markFailed(
                org.mockito.ArgumentMatchers.eq(DELIVERY_ID),
                org.mockito.ArgumentMatchers.eq(CLAIMED_AT),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acknowledgesKnownHttpResultEvenIfAuditLogWriteFails() throws IOException {
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(new DeliveryService.DeliveryAttempt(delivery, webhook, 1, CLAIMED_AT)));
        doThrow(new IllegalStateException("audit database unavailable"))
                .when(deliveryLogService).logDeliveryAttempt(
                        same(delivery), eq(1), eq(webhookUrl), org.mockito.ArgumentMatchers.anyMap(),
                        eq(PAYLOAD), org.mockito.ArgumentMatchers.any(DeliveryResult.class));
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 17L);

        verify(deliveryService).completeAttempt(DELIVERY_ID, CLAIMED_AT, true, null);
        verify(channel).basicAck(17L, false);
        verify(channel, never()).basicNack(17L, false, true);
    }

    @Test
    void acknowledgesWhenClaimOwnershipWasAlreadyLost() throws IOException {
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenReturn(Optional.of(new DeliveryService.DeliveryAttempt(delivery, webhook, 1, CLAIMED_AT)));
        when(deliveryService.completeAttempt(DELIVERY_ID, CLAIMED_AT, true, null)).thenReturn(false);
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 18L);

        verify(channel).basicAck(18L, false);
        verify(channel, never()).basicNack(18L, false, true);
        verifyNoInteractions(deliveryStatsService);
        verify(deliveryService, never()).markFailed(
                org.mockito.ArgumentMatchers.eq(DELIVERY_ID),
                org.mockito.ArgumentMatchers.eq(CLAIMED_AT),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void marksPermanentUnclaimedFailureAndAcknowledges() throws IOException {
        when(deliveryService.claimAndStartAttempt(DELIVERY_ID, WEBHOOK_ID))
                .thenThrow(new UndeliverableDeliveryException(DELIVERY_ID, "webhook missing", true));
        when(deliveryService.markUnclaimedFailed(
                DELIVERY_ID, com.projects.webhookdeliveryservice.entity.DeliveryFailureReason.UNDELIVERABLE))
                .thenReturn(true);
        Channel channel = mock(Channel.class);

        deliveryWorker.handleDelivery(message(), channel, 19L);

        verify(deliveryService).markUnclaimedFailed(
                DELIVERY_ID, com.projects.webhookdeliveryservice.entity.DeliveryFailureReason.UNDELIVERABLE);
        verify(channel).basicAck(19L, false);
        verify(channel, never()).basicNack(19L, false, true);
    }

    private DeliveryMessage message() {
        return DeliveryMessage.builder()
                .deliveryId(DELIVERY_ID)
                .webhookId(WEBHOOK_ID)
                .eventType("order.created")
                .payload(PAYLOAD)
                .attemptNumber(1)
                .build();
    }

    private void assertValidSignature(String value) {
        String[] parts = value.split(",");
        String timestamp = parts[0].substring("t=".length());
        String signature = parts[1].substring("v1=".length());
        assertEquals(hmacSignatureService.sign(SECRET, timestamp + "." + PAYLOAD), signature);
    }
}
