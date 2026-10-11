package com.projects.webhookdeliveryservice.entity;

public enum DeliveryFailureReason {
    HTTP_EXHAUSTED,
    WORKER_CRASH_EXHAUSTED,
    THROTTLE_AGE_EXCEEDED,
    UNDELIVERABLE,
    PREPARATION_ERROR,
    INFRASTRUCTURE_DEFERRAL_EXHAUSTED
}
