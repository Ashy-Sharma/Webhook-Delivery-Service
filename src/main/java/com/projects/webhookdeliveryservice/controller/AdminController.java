package com.projects.webhookdeliveryservice.controller;

import com.projects.webhookdeliveryservice.dto.response.EventPublishResponse;
import com.projects.webhookdeliveryservice.dto.response.ManualRetryResponse;
import com.projects.webhookdeliveryservice.service.AdminDeliveryService;
import com.projects.webhookdeliveryservice.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminDeliveryService adminDeliveryService;
    private final EventService eventService;

    @PostMapping("/deliveries/{deliveryId}/retry")
    public ResponseEntity<ManualRetryResponse> retryDelivery(@PathVariable Long deliveryId) {
        return ResponseEntity.ok(adminDeliveryService.retryFailedDelivery(deliveryId));
    }

    @PostMapping("/events/{eventId}/replay")
    public ResponseEntity<EventPublishResponse> replayEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(eventService.replayEvent(eventId));
    }
}
