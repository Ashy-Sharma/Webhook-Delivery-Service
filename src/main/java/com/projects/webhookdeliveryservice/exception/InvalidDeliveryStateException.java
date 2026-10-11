package com.projects.webhookdeliveryservice.exception;

public class InvalidDeliveryStateException extends RuntimeException {
    public InvalidDeliveryStateException(String message) {
        super(message);
    }
}
