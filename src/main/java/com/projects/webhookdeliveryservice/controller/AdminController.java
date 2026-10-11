package com.projects.webhookdeliveryservice.controller;

import com.projects.webhookdeliveryservice.dto.response.EventPublishResponse;
import com.projects.webhookdeliveryservice.dto.response.ManualRetryResponse;
import com.projects.webhookdeliveryservice.dto.response.DashboardResponse;
import com.projects.webhookdeliveryservice.dto.response.DeliveryHistoryPageResponse;
import com.projects.webhookdeliveryservice.dto.response.DeliveryLogResponse;
import com.projects.webhookdeliveryservice.dto.response.WebhookHealthResponse;
import com.projects.webhookdeliveryservice.service.AdminDeliveryService;
import com.projects.webhookdeliveryservice.service.DeliveryHistoryService;
import com.projects.webhookdeliveryservice.service.EventService;
import com.projects.webhookdeliveryservice.service.MonitoringService;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminDeliveryService adminDeliveryService;
    private final EventService eventService;
    private final MonitoringService monitoringService;
    private final DeliveryHistoryService deliveryHistoryService;

    @PostMapping("/deliveries/{deliveryId}/retry")
    public ResponseEntity<ManualRetryResponse> retryDelivery(@PathVariable Long deliveryId) {
        return ResponseEntity.ok(adminDeliveryService.retryFailedDelivery(deliveryId));
    }

    @PostMapping("/events/{eventId}/replay")
    public ResponseEntity<EventPublishResponse> replayEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(eventService.replayEvent(eventId));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> getDashboard() {
        return ResponseEntity.ok(monitoringService.getDashboardStats());
    }

    @GetMapping("/webhooks/health")
    public ResponseEntity<List<WebhookHealthResponse>> getWebhookHealth() {
        return ResponseEntity.ok(monitoringService.getWebhookHealthList());
    }

    @GetMapping("/webhooks/{webhookId}/deliveries")
    public ResponseEntity<DeliveryHistoryPageResponse> getWebhookDeliveries(
            @PathVariable Long webhookId,
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(deliveryHistoryService.getWebhookDeliveries(webhookId, status, page, size));
    }

    @GetMapping("/deliveries/{deliveryId}/logs")
    public ResponseEntity<List<DeliveryLogResponse>> getDeliveryLogs(
            @PathVariable Long deliveryId,
            @RequestParam(defaultValue = "false") boolean includeBody) {
        return ResponseEntity.ok(deliveryHistoryService.getDeliveryLogs(deliveryId, includeBody));
    }
}
