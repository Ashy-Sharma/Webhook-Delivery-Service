package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
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

    @Transactional
    public Optional<DeliveryAttempt> claimAndStartAttempt(Long deliveryId, Long webhookId) {
        Instant claimTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int claimed = deliveryRepository.claimForDelivery(
                deliveryId, DeliveryStatus.DELIVERING, CLAIMABLE_STATUSES, claimTime);
        if (claimed == 0) {
            deliveryRepository.failExhaustedDelivery(deliveryId, CLAIMABLE_STATUSES,
                    DeliveryStatus.FAILED, Instant.now());
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

    @Transactional
    public boolean completeAttempt(Long deliveryId, Instant claimedAt, boolean succeeded) {
        DeliveryStatus finalStatus = succeeded ? DeliveryStatus.DELIVERED : DeliveryStatus.FAILED;
        int updated = deliveryRepository.finishClaimedDelivery(deliveryId, DeliveryStatus.DELIVERING,
                claimedAt, finalStatus, succeeded ? Instant.now() : null, Instant.now());
        return updated > 0;
    }

    @Transactional
    public boolean markFailed(Long deliveryId, Instant claimedAt) {
        int updated = deliveryRepository.finishClaimedDelivery(deliveryId, DeliveryStatus.DELIVERING,
                claimedAt, DeliveryStatus.FAILED, null, Instant.now());
        return updated > 0;
    }

    @Transactional
    public boolean markUnclaimedFailed(Long deliveryId) {
        return deliveryRepository.failUnclaimedDelivery(deliveryId, CLAIMABLE_STATUSES,
                DeliveryStatus.FAILED, Instant.now()) > 0;
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
                DeliveryStatus.RETRYING, threshold, now.plusSeconds(60), now) > 0;
    }

    @Transactional
    public boolean rescheduleRetryDispatch(Long deliveryId, Instant threshold) {
        Instant now = Instant.now();
        return deliveryRepository.rescheduleRetryDispatch(deliveryId, DeliveryStatus.RETRYING,
                threshold, now.plusSeconds(60), now) > 0;
    }

    public record DeliveryAttempt(Delivery delivery, Webhook webhook, int attemptNumber, Instant claimedAt) {
    }
}
