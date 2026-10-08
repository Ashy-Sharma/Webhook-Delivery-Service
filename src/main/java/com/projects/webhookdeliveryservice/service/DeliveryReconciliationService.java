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

    private final DeliveryRepository deliveryRepository;
    private final EventService eventService;

    @Scheduled(fixedDelay = 300_000)
    public void reconcileOrphanedDeliveries() {
        Instant staleThreshold = Instant.now().minus(STALE_AFTER_MINUTES, ChronoUnit.MINUTES);
        List<Delivery> pendingDeliveries = deliveryRepository
                .findByStatusAndCreatedAtBefore(DeliveryStatus.PENDING, staleThreshold);

        for (Delivery delivery : pendingDeliveries) {
            if (delivery.getReconciliationAttempts() >= MAX_RECONCILIATION_ATTEMPTS) {
                log.error("Delivery {} exceeded reconciliation attempts and requires manual intervention",
                        delivery.getId());
                continue;
            }

            delivery.setReconciliationAttempts(delivery.getReconciliationAttempts() + 1);
            deliveryRepository.save(delivery);
            eventService.publishDelivery(delivery);
        }
    }
}
