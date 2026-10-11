package com.projects.webhookdeliveryservice.exception;

import java.util.List;

public class InvalidEventTypeException extends RuntimeException {

    public InvalidEventTypeException(String eventType, List<String> allowedEventTypes) {
        super("Unknown event type: " + eventType + ". Allowed types: " + allowedEventTypes);
    }
}
