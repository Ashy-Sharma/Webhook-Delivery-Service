package com.projects.webhookdeliveryservice.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RetryConfig {

    @Value("${webhook.retry.max-attempts:5}")
    private int maxAttempts;

    @Value("${webhook.retry.delays:2000,10000,30000}")
    private List<Long> delays;

    @PostConstruct
    void validate() {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("webhook.retry.max-attempts must be at least 1");
        }
        if (delays.size() != 3 || delays.stream().anyMatch(delay -> delay == null || delay <= 0)
                || delays.get(0) >= delays.get(1) || delays.get(1) >= delays.get(2)) {
            throw new IllegalArgumentException(
                    "webhook.retry.delays must contain three increasing positive millisecond values");
        }
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getDelayMillis(int tierIndex) {
        if (tierIndex < 0 || tierIndex >= delays.size()) {
            throw new IllegalArgumentException("Retry delay tier index is out of range: " + tierIndex);
        }
        return delays.get(Math.min(tierIndex, delays.size() - 1));
    }

    public long getDelayForFailedAttempt(int failedAttemptNumber) {
        return getDelayMillis(Math.min(Math.max(0, failedAttemptNumber - 1), delays.size() - 1));
    }

    public String getRetryQueueName(int failedAttemptNumber) {
        return "webhook.retry.tier" + getTierForFailedAttempt(failedAttemptNumber);
    }

    public String getRetryRoutingKey(int failedAttemptNumber) {
        return getRetryQueueName(failedAttemptNumber);
    }

    public String getRetryQueueNameForTier(int tier) {
        if (tier < 1 || tier > delays.size()) {
            throw new IllegalArgumentException("Retry tier must be between 1 and " + delays.size());
        }
        return "webhook.retry.tier" + tier;
    }

    public String getRetryRoutingKeyForTier(int tier) {
        return getRetryQueueNameForTier(tier);
    }

    /** Selects the shortest fixed-TTL tier that can cover the requested wait. */
    public int getTierForWait(long requestedWaitMillis) {
        long wait = Math.max(0, requestedWaitMillis);
        for (int tierIndex = 0; tierIndex < delays.size(); tierIndex++) {
            if (delays.get(tierIndex) >= wait) {
                return tierIndex + 1;
            }
        }
        return delays.size();
    }

    private int getTierForFailedAttempt(int failedAttemptNumber) {
        return Math.min(Math.max(failedAttemptNumber, 1), delays.size());
    }
}
