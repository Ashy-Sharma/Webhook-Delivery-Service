package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.exception.InvalidDeliveryStateException;
import com.projects.webhookdeliveryservice.exception.ResourceNotFoundException;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminDeliveryServiceTest {

    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final EventService eventService = mock(EventService.class);
    private final AdminDeliveryService service = new AdminDeliveryService(deliveryRepository, eventService);

    @Test
    void resetsFailedDeliveryAndSchedulesItsMessageAfterCommit() {
        Delivery delivery = Delivery.builder().id(23L).status(DeliveryStatus.PENDING).attemptCount(0).build();
        when(deliveryRepository.resetFailedDeliveryForManualRetry(
                org.mockito.ArgumentMatchers.eq(23L), org.mockito.ArgumentMatchers.eq(DeliveryStatus.FAILED),
                org.mockito.ArgumentMatchers.eq(DeliveryStatus.PENDING), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        when(deliveryRepository.findDetailedById(23L)).thenReturn(Optional.of(delivery));

        var response = service.retryFailedDelivery(23L);

        assertThat(response.deliveryId()).isEqualTo(23L);
        assertThat(response.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(response.attemptCount()).isZero();
        verify(eventService).publishAfterCommit(delivery);
    }

    @Test
    void rejectsRetryWhenDeliveryIsNotFailed() {
        when(deliveryRepository.resetFailedDeliveryForManualRetry(
                org.mockito.ArgumentMatchers.eq(23L), org.mockito.ArgumentMatchers.eq(DeliveryStatus.FAILED),
                org.mockito.ArgumentMatchers.eq(DeliveryStatus.PENDING), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        when(deliveryRepository.findById(23L))
                .thenReturn(Optional.of(Delivery.builder().id(23L).status(DeliveryStatus.DELIVERED).build()));

        assertThatThrownBy(() -> service.retryFailedDelivery(23L))
                .isInstanceOf(InvalidDeliveryStateException.class)
                .hasMessageContaining("DELIVERED");
        verify(eventService, never()).publishAfterCommit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsNotFoundForUnknownDelivery() {
        when(deliveryRepository.resetFailedDeliveryForManualRetry(
                org.mockito.ArgumentMatchers.eq(404L), org.mockito.ArgumentMatchers.eq(DeliveryStatus.FAILED),
                org.mockito.ArgumentMatchers.eq(DeliveryStatus.PENDING), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        when(deliveryRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryFailedDelivery(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
