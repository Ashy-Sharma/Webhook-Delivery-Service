package com.projects.webhookdeliveryservice.worker;

import com.rabbitmq.client.Channel;
import com.projects.webhookdeliveryservice.dto.DeliveryMessage;
import com.projects.webhookdeliveryservice.dto.DeliveryResult;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.exception.UndeliverableDeliveryException;
import com.projects.webhookdeliveryservice.service.DeliveryLogService;
import com.projects.webhookdeliveryservice.service.DeliveryService;
import com.projects.webhookdeliveryservice.service.HmacSignatureService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import io.netty.handler.timeout.ReadTimeoutException;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final String USER_AGENT = "WebhookService/1.0";
    private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(15);
    private static final int MAX_RESPONSE_BODY_BYTES = 4 * 1024;
    private static final long REQUEUE_BACKOFF_MILLIS = 2_000;

    private final DeliveryService deliveryService;
    private final DeliveryLogService deliveryLogService;
    private final HmacSignatureService hmacSignatureService;
    private final WebClient webhookWebClient;

    @RabbitListener(queues = "${webhook.rabbitmq.delivery-queue}")
    public void handleDelivery(
            DeliveryMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        Optional<DeliveryService.DeliveryAttempt> claimed;
        try {
            claimed = deliveryService.claimAndStartAttempt(message.getDeliveryId(), message.getWebhookId());
        } catch (UndeliverableDeliveryException exception) {
            if (exception.shouldMarkFailed()) {
                try {
                    deliveryService.markUnclaimedFailed(exception.getDeliveryId());
                } catch (Exception statusException) {
                    log.error("Could not mark undeliverable delivery {} as FAILED; requeuing",
                            exception.getDeliveryId(), statusException);
                    nackAfterBackoff(channel, deliveryTag);
                    return;
                }
            }
            log.error("Dropping invalid delivery message for delivery {}: {}",
                    exception.getDeliveryId(), exception.getMessage());
            channel.basicAck(deliveryTag, false);
            return;
        } catch (Exception exception) {
            log.error("Could not claim delivery {}; requeuing message", message.getDeliveryId(), exception);
            nackAfterBackoff(channel, deliveryTag);
            return;
        }

        if (claimed.isEmpty()) {
            log.info("Skipping duplicate, cancelled, or completed delivery message for delivery {}",
                    message.getDeliveryId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        DeliveryService.DeliveryAttempt attempt = claimed.get();
        Delivery delivery = attempt.delivery();
        Webhook webhook = attempt.webhook();
        Map<String, String> headers;
        DeliveryResult result;
        try {
            long timestamp = Instant.now().getEpochSecond();
            String signedContent = timestamp + "." + message.getPayload();
            String signature = hmacSignatureService.sign(webhook.getSecretKey(), signedContent);
            String signatureHeader = "t=" + timestamp + ",v1=" + signature;

            headers = buildHeaders(message, timestamp, signatureHeader);
            result = sendRequest(webhook.getUrl(), message.getPayload(), headers);
        } catch (Exception exception) {
            log.error("Could not prepare request for claimed delivery {}", delivery.getId(), exception);
            try {
                if (!deliveryService.markFailed(delivery.getId(), attempt.claimedAt())) {
                    log.warn("Delivery {} is no longer owned by this attempt", delivery.getId());
                }
            } catch (Exception statusException) {
                log.error("Could not mark delivery {} as FAILED; requeuing for lease recovery",
                        delivery.getId(), statusException);
                nackAfterBackoff(channel, deliveryTag);
                return;
            }
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            deliveryLogService.logDeliveryAttempt(
                    delivery,
                    attempt.attemptNumber(),
                    webhook.getUrl(),
                    headers,
                    message.getPayload(),
                    result);
        } catch (Exception logException) {
            // Persisting the audit row must not discard the known HTTP result.
            log.error("Could not save delivery log for delivery {} (HTTP status {}, success={})",
                    delivery.getId(), result.statusCode(), result.success(), logException);
        }

        boolean completionPersisted;
        try {
            completionPersisted = deliveryService.completeAttempt(
                    delivery.getId(), attempt.claimedAt(), result.success());
        } catch (Exception persistenceException) {
            // The HTTP result is known. Do not convert a successful POST into FAILED;
            // the lease recovery will retry this attempt if persistence remains unavailable.
            log.error("Could not persist HTTP result for delivery {}; leaving claim for recovery",
                    delivery.getId(), persistenceException);
            nackAfterBackoff(channel, deliveryTag);
            return;
        }

        if (completionPersisted) {
            if (result.success()) {
                log.info("Delivered webhook delivery {} with HTTP status {} in {} ms",
                        delivery.getId(), result.statusCode(), result.responseTimeMs());
            } else {
                log.warn("Webhook delivery {} failed with status {}: {}",
                        delivery.getId(), result.statusCode(), result.errorMessage());
            }
            channel.basicAck(deliveryTag, false);
        } else {
            log.warn("Delivery {} is no longer owned by this attempt; another worker is responsible",
                    delivery.getId());
            channel.basicAck(deliveryTag, false);
        }
    }

    private void nackAfterBackoff(Channel channel, long deliveryTag) throws IOException {
        try {
            Thread.sleep(REQUEUE_BACKOFF_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while delaying RabbitMQ requeue for delivery tag {}", deliveryTag);
        }
        channel.basicNack(deliveryTag, false, true);
    }

    private Map<String, String> buildHeaders(DeliveryMessage message, long timestamp, String signature) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Webhook-Signature", signature);
        headers.put("X-Webhook-Event", message.getEventType());
        headers.put("X-Webhook-Delivery-Id", message.getDeliveryId().toString());
        headers.put("X-Webhook-Timestamp", Long.toString(timestamp));
        headers.put("User-Agent", USER_AGENT);
        return headers;
    }

    private DeliveryResult sendRequest(String url, String payload, Map<String, String> headers) {
        long startNanos = System.nanoTime();
        try {
            HttpResponse response = webhookWebClient.post()
                    .uri(url)
                    .headers(httpHeaders -> headers.forEach(httpHeaders::set))
                    .bodyValue(payload)
                    .exchangeToMono(clientResponse -> clientResponse.bodyToFlux(DataBuffer.class)
                            .scan(new CappedResponseBody(), CappedResponseBody::append)
                            .takeUntil(CappedResponseBody::isFull)
                            .last(new CappedResponseBody())
                            .map(body -> new HttpResponse(clientResponse.statusCode().value(), body.asString())))
                    .block(REQUEST_DEADLINE);

            if (response == null) {
                throw new IllegalStateException("Subscriber request completed without an HTTP response");
            }

            long responseTimeMs = elapsedMillis(startNanos);
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            return new DeliveryResult(response.statusCode(), response.body(), responseTimeMs, null, success);
        } catch (Exception exception) {
            return new DeliveryResult(0, null, elapsedMillis(startNanos), describeFailure(exception), false);
        }
    }

    private String describeFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ReadTimeoutException) {
                return "ReadTimeoutException: Subscriber response timed out";
            }
        }

        if (exception instanceof IllegalStateException
                && exception.getMessage() != null
                && exception.getMessage().startsWith("Timeout on blocking read")) {
            return "TimeoutException: Webhook request exceeded the 15-second deadline";
        }

        if (exception instanceof WebClientRequestException) {
            return exceptionDescription("WebClientRequestException", exception.getMessage());
        }

        return exceptionDescription(exception.getClass().getSimpleName(), exception.getMessage());
    }

    private String exceptionDescription(String exceptionType, String message) {
        return exceptionType + ": " + (message == null || message.isBlank() ? "No error details available" : message);
    }

    private long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private record HttpResponse(int statusCode, String body) {
    }

    private static final class CappedResponseBody {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(MAX_RESPONSE_BODY_BYTES);

        private CappedResponseBody append(DataBuffer dataBuffer) {
            try {
                int remaining = MAX_RESPONSE_BODY_BYTES - bytes.size();
                if (remaining > 0) {
                    int capturedBytes = Math.min(remaining, dataBuffer.readableByteCount());
                    byte[] chunk = new byte[capturedBytes];
                    dataBuffer.read(chunk);
                    bytes.write(chunk, 0, capturedBytes);
                }
                return this;
            } finally {
                DataBufferUtils.release(dataBuffer);
            }
        }

        private String asString() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private boolean isFull() {
            return bytes.size() >= MAX_RESPONSE_BODY_BYTES;
        }
    }
}
