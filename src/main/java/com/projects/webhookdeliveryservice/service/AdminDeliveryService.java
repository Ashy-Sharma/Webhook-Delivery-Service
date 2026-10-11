package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.dto.response.ManualRetryResponse;
import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.exception.InvalidDeliveryStateException;
import com.projects.webhookdeliveryservice.exception.ResourceNotFoundException;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AdminDeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final EventService eventService;

    @Transactional
    public ManualRetryResponse retryFailedDelivery(Long deliveryId) {
        int reset = deliveryRepository.resetFailedDeliveryForManualRetry(
                deliveryId, DeliveryStatus.FAILED, DeliveryStatus.PENDING, Instant.now());
        if (reset == 0) {
            Delivery current = deliveryRepository.findById(deliveryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Delivery not found"));
            throw new InvalidDeliveryStateException(
                    "Cannot retry delivery in status " + current.getStatus());
        }

        Delivery delivery = deliveryRepository.findDetailedById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found"));
        eventService.publishAfterCommit(delivery);
        return new ManualRetryResponse(deliveryId, DeliveryStatus.PENDING, 0);
    }
}
