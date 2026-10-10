package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.dto.DeliveryMessage;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryFailureReason;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class RetryService {

    private static final Logger log = LoggerFactory.getLogger(RetryService.class);
    private static final String DLQ_ROUTING_KEY = "webhook.dlq";

    private final RetryConfig retryConfig;
    private final DeliveryService deliveryService;
    private final ConfirmedRabbitPublisher rabbitPublisher;

    @Value("${webhook.rabbitmq.exchange}")
    private String exchange;

    @Value("${webhook.rabbitmq.dlq-queue:webhook.dlq}")
    private String dlqQueueName;

    @Value("${webhook.ratelimit.max-throttle-age-ms:900000}")
    private long maxThrottleAgeMillis;

    @PostConstruct
    void validateThrottleLimit() {
        if (maxThrottleAgeMillis < 1) {
            throw new IllegalArgumentException("webhook.ratelimit.max-throttle-age-ms must be at least 1");
        }
    }

    public RetryOutcome handleFailure(DeliveryService.DeliveryAttempt attempt, DeliveryMessage originalMessage) {
        Delivery delivery = attempt.delivery();
        int attemptNumber = attempt.attemptNumber();

        if (attemptNumber >= delivery.getMaxAttempts()) {
            // Publish before finalizing the DB state. If RabbitMQ is unavailable, leave
            // the claim recoverable instead of recording terminal failure and losing DLQ data.
            rabbitPublisher.publish(exchange, DLQ_ROUTING_KEY, retryMessage(originalMessage, attemptNumber));
            boolean failed = deliveryService.completeAttempt(delivery.getId(), attempt.claimedAt(), false,
                    DeliveryFailureReason.HTTP_EXHAUSTED);
            if (!failed) {
                log.warn("Delivery {} lost its claim while routing to DLQ {}; duplicate DLQ entry is possible",
                        delivery.getId(), dlqQueueName);
                return RetryOutcome.OWNERSHIP_LOST;
            }
            log.error("Delivery {} permanently failed after {} attempts and was routed to {}",
                    delivery.getId(), attemptNumber, dlqQueueName);
            return RetryOutcome.DEAD_LETTERED;
        }

        long delayMillis = retryConfig.getDelayForFailedAttempt(attemptNumber);
        Instant nextRetryAt = Instant.now().plusMillis(delayMillis);
        boolean scheduled = deliveryService.scheduleRetry(delivery.getId(), attempt.claimedAt(), nextRetryAt);
        if (!scheduled) {
            log.warn("Delivery {} is no longer owned by this attempt; another process is responsible",
                    delivery.getId());
            return RetryOutcome.OWNERSHIP_LOST;
        }

        String routingKey = retryConfig.getRetryRoutingKey(attemptNumber);
        // The RETRYING row and next_retry_at are committed first. If publish fails,
        // the worker requeues the original message and the existing redispatch scan
        // will publish it after the deadline plus its grace period.
        rabbitPublisher.publish(exchange, routingKey, retryMessage(originalMessage, attemptNumber + 1));
        log.warn("Delivery {} failed on attempt {}; routed to {} with {} ms delay",
                delivery.getId(), attemptNumber, routingKey, delayMillis);
        return RetryOutcome.RETRY_SCHEDULED;
    }

    /**
     * Releases the HTTP-attempt claim when a future rate limiter blocks the request.
     * The database attempt count is refunded, while next_retry_at remains the durable
     * recovery deadline if the retry-queue publish cannot be completed.
     */
    /**
     * @param estimatedWaitMillis time until the oldest rate-limit entry expires
     */
    public RetryOutcome handleThrottled(DeliveryService.DeliveryAttempt attempt,
                                        DeliveryMessage originalMessage,
                                        long estimatedWaitMillis) {
        int initialTier = retryConfig.getTierForWait(Math.max(0, estimatedWaitMillis));
        long initialTierDelay = retryConfig.getDelayMillis(initialTier - 1);
        long maxJitterMillis = Math.max(1, initialTierDelay / 5);
        long jitterMillis = ThreadLocalRandom.current().nextLong(maxJitterMillis + 1);
        int tier = retryConfig.getTierForWait(Math.max(0, estimatedWaitMillis) + jitterMillis);
        long delayMillis = retryConfig.getDelayMillis(tier - 1);
        Instant nextRetryAt = Instant.now().plusMillis(delayMillis);
        Instant throttleDeadline = Instant.now().minus(Duration.ofMillis(maxThrottleAgeMillis));
        Instant throttledSince = attempt.delivery().getThrottledSince();
        if (throttledSince != null && !throttledSince.isAfter(throttleDeadline)) {
            // Preserve the terminal message before changing the row to FAILED.
            // If publishing fails, the worker can requeue and the lease can recover it.
            rabbitPublisher.publish(exchange, DLQ_ROUTING_KEY,
                    retryMessage(originalMessage, attempt.attemptNumber()));
        }
        DeliveryService.ThrottleReleaseResult releaseResult = deliveryService.releaseClaimForThrottle(
                attempt, nextRetryAt, throttleDeadline,
                maxThrottleAgeMillis);
        if (releaseResult == DeliveryService.ThrottleReleaseResult.OWNERSHIP_LOST) {
            log.warn("Throttled delivery {} is no longer owned by this attempt",
                    attempt.delivery().getId());
            return RetryOutcome.OWNERSHIP_LOST;
        }
        if (releaseResult == DeliveryService.ThrottleReleaseResult.LIMIT_REACHED) {
            log.error("Delivery {} exceeded the maximum throttle age of {} ms, was written to the DLQ, " +
                            "and is now FAILED", attempt.delivery().getId(), maxThrottleAgeMillis);
            return RetryOutcome.THROTTLE_LIMIT_REACHED;
        }

        String routingKey = retryConfig.getRetryRoutingKeyForTier(tier);
        rabbitPublisher.publish(exchange, routingKey,
                retryMessage(originalMessage, attempt.attemptNumber()));
        log.info("Delivery {} was rate limited; released the claim and routed to {} for {} ms " +
                        "(estimated wait {} ms plus {} ms jitter)",
                attempt.delivery().getId(), routingKey, delayMillis, estimatedWaitMillis, jitterMillis);
        return RetryOutcome.THROTTLE_REQUEUED;
    }

    private DeliveryMessage retryMessage(DeliveryMessage originalMessage, int nextAttemptNumber) {
        return DeliveryMessage.builder()
                .deliveryId(originalMessage.getDeliveryId())
                .webhookId(originalMessage.getWebhookId())
                .eventType(originalMessage.getEventType())
                .payload(originalMessage.getPayload())
                .attemptNumber(nextAttemptNumber)
                .build();
    }

    public enum RetryOutcome {
        RETRY_SCHEDULED,
        THROTTLE_REQUEUED,
        THROTTLE_LIMIT_REACHED,
        DEAD_LETTERED,
        OWNERSHIP_LOST
    }
}
