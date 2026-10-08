package com.projects.webhookdeliveryservice.exception;

public class UndeliverableDeliveryException extends RuntimeException {

    private final Long deliveryId;
    private final boolean shouldMarkFailed;

    public UndeliverableDeliveryException(Long deliveryId, String message, boolean shouldMarkFailed) {
        super(message);
        this.deliveryId = deliveryId;
        this.shouldMarkFailed = shouldMarkFailed;
    }

    public Long getDeliveryId() {
        return deliveryId;
    }

    public boolean shouldMarkFailed() {
        return shouldMarkFailed;
    }
}
