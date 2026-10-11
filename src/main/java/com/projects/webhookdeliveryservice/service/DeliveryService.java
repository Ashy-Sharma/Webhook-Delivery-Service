package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.DeliveryFailureReason;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.exception.UndeliverableDeliveryException;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeliveryService {

    private static final List<DeliveryStatus> CLAIMABLE_STATUSES =
            List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING);

    private final DeliveryRepository deliveryRepository;
    private final WebhookRepository webhookRepository;
    private final DeliveryLogService deliveryLogService;

    @Transactional
    public Optional<DeliveryAttempt> claimAndStartAttempt(Long deliveryId, Long webhookId) {
        Instant claimTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int claimed = deliveryRepository.claimForDelivery(
                deliveryId, DeliveryStatus.DELIVERING, CLAIMABLE_STATUSES, claimTime, DeliveryStatus.RETRYING);
        if (claimed == 0) {
            return Optional.empty();
        }

        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new UndeliverableDeliveryException(deliveryId,
                        "Claimed delivery could not be reloaded", false));

        if (!delivery.getWebhook().getId().equals(webhookId)) {
            throw new UndeliverableDeliveryException(deliveryId,
                    "Delivery message webhook does not match its delivery record", false);
        }

        Webhook webhook = webhookRepository.findById(webhookId)
                .orElseThrow(() -> new UndeliverableDeliveryException(deliveryId,
                        "Webhook " + webhookId + " no longer exists", true));

        return Optional.of(new DeliveryAttempt(delivery, webhook, delivery.getAttemptCount(),
                delivery.getClaimedAt()));
    }

    /**
     * Takes a fenced claim for terminalizing a delivery whose worker claim budget
     * is exhausted. This does not increment attemptCount: no new HTTP attempt starts.
     */
    @Transactional
    public Optional<DeliveryAttempt> claimExhaustedDeliveryForDlq(Long deliveryId, Long webhookId) {
        Instant claimTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int claimed = deliveryRepository.claimExhaustedDeliveryForDlq(deliveryId, webhookId,
                DeliveryStatus.DELIVERING, CLAIMABLE_STATUSES, claimTime);
        if (claimed == 0) {
            return Optional.empty();
        }

        Delivery delivery = deliveryRepository.findDetailedById(deliveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed crash-exhausted delivery could not be reloaded: " + deliveryId));
        return Optional.of(new DeliveryAttempt(delivery, delivery.getWebhook(),
                delivery.getAttemptCount(), delivery.getClaimedAt()));
    }

    @Transactional
    public void recordInfrastructureDeferral(Long deliveryId, Instant deferredSince) {
        deliveryRepository.setInfrastructureDeferredSince(deliveryId, deferredSince);
    }

    @Transactional
    public Optional<DeliveryAttempt> claimExpiredInfrastructureDeferral(
            Long deliveryId, Long webhookId, Instant deferredSince, Instant deadline) {
        Instant claimTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int claimed = deliveryRepository.claimExpiredInfrastructureDeferral(deliveryId, webhookId,
                DeliveryStatus.DELIVERING, CLAIMABLE_STATUSES, DeliveryStatus.RETRYING,
                claimTime, deferredSince, deadline);
        if (claimed == 0) {
            return Optional.empty();
        }
        Delivery delivery = deliveryRepository.findDetailedById(deliveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "Infrastructure-exhausted delivery could not be reloaded: " + deliveryId));
        return Optional.of(new DeliveryAttempt(delivery, delivery.getWebhook(),
                delivery.getAttemptCount(), delivery.getClaimedAt()));
    }

    @Transactional
    public boolean failInfrastructureDeferredDelivery(DeliveryAttempt attempt) {
        boolean failed = finishClaimedDelivery(attempt, DeliveryFailureReason.INFRASTRUCTURE_DEFERRAL_EXHAUSTED);
        if (!failed) {
            return false;
        }
        deliveryLogService.logInfrastructureDeferralExhausted(attempt.delivery(),
                attempt.webhook().getUrl());
        return true;
    }

    /** Persists the crash-exhaustion reason and audit entry in one database transaction. */
    @Transactional
    public boolean failCrashExhaustedDelivery(DeliveryAttempt attempt) {
        boolean failed = finishClaimedDelivery(attempt, DeliveryFailureReason.WORKER_CRASH_EXHAUSTED);
        if (!failed) {
            return false;
        }
        deliveryLogService.logWorkerCrashExhausted(attempt.delivery(), attempt.webhook().getUrl(),
                attempt.attemptNumber());
        return true;
    }

    @Transactional
    public boolean completeAttempt(Long deliveryId, Instant claimedAt, boolean succeeded,
                                   DeliveryFailureReason failureReason) {
        DeliveryStatus finalStatus = succeeded ? DeliveryStatus.DELIVERED : DeliveryStatus.FAILED;
        int updated = deliveryRepository.finishClaimedDelivery(deliveryId, DeliveryStatus.DELIVERING,
                claimedAt, finalStatus, succeeded ? null : failureReason,
                succeeded ? Instant.now() : null, Instant.now());
        return updated > 0;
    }


    @Transactional
    public boolean scheduleRetry(Long deliveryId, Instant claimedAt, Instant nextRetryAt) {
        return deliveryRepository.scheduleRetry(deliveryId, DeliveryStatus.DELIVERING, claimedAt,
                DeliveryStatus.RETRYING, nextRetryAt, Instant.now()) > 0;
    }

    @Transactional
    public ThrottleReleaseResult releaseClaimForThrottle(
            DeliveryAttempt attempt, Instant nextRetryAt, Instant throttleDeadline, long maxThrottleAgeMillis) {
        Long deliveryId = attempt.delivery().getId();
        Instant claimedAt = attempt.claimedAt();
        int released = deliveryRepository.releaseClaimForThrottle(deliveryId, DeliveryStatus.DELIVERING,
                claimedAt, DeliveryStatus.RETRYING, nextRetryAt, Instant.now(), throttleDeadline);
        if (released > 0) {
            return ThrottleReleaseResult.REQUEUED;
        }

        int failed = deliveryRepository.failDeliveryAfterThrottleAge(deliveryId, DeliveryStatus.DELIVERING,
                claimedAt, DeliveryStatus.FAILED, DeliveryFailureReason.THROTTLE_AGE_EXCEEDED,
                Instant.now(), throttleDeadline);
        if (failed > 0) {
            deliveryLogService.logThrottleAgeLimitExceeded(
                    attempt.delivery(), attempt.webhook().getUrl(), maxThrottleAgeMillis);
            return ThrottleReleaseResult.LIMIT_REACHED;
        }
        return ThrottleReleaseResult.OWNERSHIP_LOST;
    }

    @Transactional
    public boolean markFailed(Long deliveryId, Instant claimedAt, DeliveryFailureReason failureReason) {
        int updated = deliveryRepository.finishClaimedDelivery(deliveryId, DeliveryStatus.DELIVERING,
                claimedAt, DeliveryStatus.FAILED, failureReason, null, Instant.now());
        return updated > 0;
    }

    private boolean finishClaimedDelivery(DeliveryAttempt attempt, DeliveryFailureReason failureReason) {
        int updated = deliveryRepository.finishClaimedDelivery(attempt.delivery().getId(),
                DeliveryStatus.DELIVERING, attempt.claimedAt(), DeliveryStatus.FAILED,
                failureReason, null, Instant.now());
        return updated > 0;
    }

    @Transactional
    public boolean markUnclaimedFailed(Long deliveryId, DeliveryFailureReason failureReason) {
        return deliveryRepository.failUnclaimedDelivery(deliveryId, CLAIMABLE_STATUSES,
                DeliveryStatus.FAILED, failureReason, Instant.now()) > 0;
    }

    @Transactional
    public boolean bumpReconciliationAttempt(Long deliveryId, int maxAttempts) {
        return deliveryRepository.bumpReconciliationAttempt(
                deliveryId, DeliveryStatus.PENDING, maxAttempts) > 0;
    }

    @Transactional
    public boolean releaseExpiredClaim(Long deliveryId, Instant threshold) {
        Instant now = Instant.now();
        return deliveryRepository.releaseExpiredClaim(deliveryId, DeliveryStatus.DELIVERING,
                DeliveryStatus.RETRYING, threshold, now) > 0;
    }

    @Transactional
    public boolean rescheduleRetryDispatch(Long deliveryId, Instant threshold) {
        Instant now = Instant.now();
        return deliveryRepository.rescheduleRetryDispatch(deliveryId, DeliveryStatus.RETRYING,
                threshold, now) > 0;
    }

    public record DeliveryAttempt(Delivery delivery, Webhook webhook, int attemptNumber, Instant claimedAt) {
    }

    public enum ThrottleReleaseResult {
        REQUEUED,
        LIMIT_REACHED,
        OWNERSHIP_LOST
    }
}
