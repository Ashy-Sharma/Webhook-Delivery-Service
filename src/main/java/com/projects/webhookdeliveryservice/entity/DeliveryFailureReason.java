package com.projects.webhookdeliveryservice.entity;

public enum DeliveryFailureReason {
    HTTP_EXHAUSTED,
    THROTTLE_AGE_EXCEEDED,
    UNDELIVERABLE,
    PREPARATION_ERROR
}
