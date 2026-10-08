package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DeliveryReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryReconciliationService.class);
    private static final int MAX_RECONCILIATION_ATTEMPTS = 5;
    private static final int STALE_AFTER_MINUTES = 2;
    private static final int CLAIM_LEASE_SECONDS = 60;
    private static final int RETRY_REDISPATCH_GRACE_SECONDS = 30;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryService deliveryService;
    private final EventService eventService;

    @Scheduled(fixedDelay = 300_000)
    public void reconcileOrphanedDeliveries() {
        log.info("Starting orphan delivery reconciliation for PENDING rows older than {} minutes",
                STALE_AFTER_MINUTES);
        List<Delivery> pendingDeliveries = deliveryRepository.findStalePendingDeliveries();
        log.info("Found {} stale PENDING deliveries for reconciliation", pendingDeliveries.size());

        for (Delivery delivery : pendingDeliveries) {
            if (delivery.getReconciliationAttempts() >= MAX_RECONCILIATION_ATTEMPTS) {
                log.error("Delivery {} exceeded reconciliation attempts and requires manual intervention",
                        delivery.getId());
                continue;
            }

            boolean attemptReserved = deliveryService.bumpReconciliationAttempt(
                    delivery.getId(), MAX_RECONCILIATION_ATTEMPTS);
            if (attemptReserved) {
                log.info("Reserved reconciliation attempt {} for delivery {}",
                        delivery.getReconciliationAttempts() + 1, delivery.getId());
                try {
                    eventService.publishDelivery(delivery);
                    log.info("Republished orphan delivery {}", delivery.getId());
                } catch (RuntimeException exception) {
                    log.error("Could not republish orphan delivery {} after reserving its reconciliation attempt",
                            delivery.getId(), exception);
                    throw exception;
                }
            } else {
                log.info("Skipped reconciliation publish for delivery {}; its state or attempt limit changed",
                        delivery.getId());
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void recoverExpiredClaims() {
        Instant now = Instant.now();
        Instant claimThreshold = now.minus(CLAIM_LEASE_SECONDS, ChronoUnit.SECONDS);
        List<Delivery> expiredClaims = deliveryRepository
                .findByStatusAndClaimedAtBefore(DeliveryStatus.DELIVERING, claimThreshold);

        for (Delivery delivery : expiredClaims) {
            if (!deliveryService.releaseExpiredClaim(delivery.getId(), claimThreshold)) {
                continue;
            }

            try {
                eventService.publishDelivery(delivery);
                log.warn("Released expired claim for delivery {} and republished it", delivery.getId());
            } catch (Exception exception) {
                log.error("Could not republish expired delivery {}; it will be retried by reconciliation",
                        delivery.getId(), exception);
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void republishExpiredRetryMessages() {
        Instant threshold = Instant.now().minus(RETRY_REDISPATCH_GRACE_SECONDS, ChronoUnit.SECONDS);
        List<Delivery> retryingDeliveries = deliveryRepository
                .findByStatusAndNextRetryAtBefore(DeliveryStatus.RETRYING, threshold);

        for (Delivery delivery : retryingDeliveries) {
            if (!deliveryService.rescheduleRetryDispatch(delivery.getId(), threshold)) {
                continue;
            }
            try {
                eventService.publishDelivery(delivery);
                log.warn("Republished unclaimed retry delivery {}", delivery.getId());
            } catch (Exception exception) {
                log.error("Could not republish retry delivery {}; it will be retried by reconciliation",
                        delivery.getId(), exception);
            }
        }
    }

}
