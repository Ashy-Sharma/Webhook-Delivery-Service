package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryReconciliationServiceTest {

    private static final int MAX_RECONCILIATION_ATTEMPTS = 5;
    private DeliveryRepository deliveryRepository;
    private DeliveryService deliveryService;
    private EventService eventService;
    private DeliveryReconciliationService reconciliationService;
    private Delivery pendingDelivery;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(DeliveryRepository.class);
        deliveryService = mock(DeliveryService.class);
        eventService = mock(EventService.class);
        reconciliationService = new DeliveryReconciliationService(
                deliveryRepository, deliveryService, eventService);
        pendingDelivery = Delivery.builder()
                .id(23L)
                .status(DeliveryStatus.PENDING)
                .reconciliationAttempts(0)
                .build();
        when(deliveryRepository.findStalePendingDeliveries())
                .thenReturn(List.of(pendingDelivery));
    }

    @Test
    void doesNotPublishWhenAtomicBumpLosesRaceWithWorkerClaim() {
        when(deliveryService.bumpReconciliationAttempt(23L, MAX_RECONCILIATION_ATTEMPTS))
                .thenReturn(false);

        reconciliationService.reconcileOrphanedDeliveries();

        verify(eventService, never()).publishDelivery(any(Delivery.class));
        verify(deliveryRepository, never()).save(any(Delivery.class));
        verify(deliveryService).bumpReconciliationAttempt(23L, MAX_RECONCILIATION_ATTEMPTS);
    }

    @Test
    void publishesOnlyAfterAtomicReconciliationBumpSucceeds() {
        when(deliveryService.bumpReconciliationAttempt(23L, MAX_RECONCILIATION_ATTEMPTS))
                .thenReturn(true);

        reconciliationService.reconcileOrphanedDeliveries();

        verify(eventService).publishDelivery(pendingDelivery);
        verify(deliveryRepository, never()).save(any(Delivery.class));
    }
}
