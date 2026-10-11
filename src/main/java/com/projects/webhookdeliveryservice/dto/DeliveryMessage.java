package com.projects.webhookdeliveryservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryMessage {

    private Long deliveryId;
    private Long webhookId;
    private String eventType;
    private String payload;
    private int attemptNumber;
    private Instant infrastructureDeferredSince;
}
