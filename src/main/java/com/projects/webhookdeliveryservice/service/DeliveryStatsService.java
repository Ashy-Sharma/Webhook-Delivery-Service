package com.projects.webhookdeliveryservice.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Best-effort Redis counters for terminal HTTP delivery outcomes. MySQL remains
 * the source of truth; a Redis outage must never change a persisted delivery result.
 */
@Service
@RequiredArgsConstructor
public class DeliveryStatsService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatsService.class);
    private static final long LATENCY_HISTORY_SIZE = 100;

    private final StringRedisTemplate redisTemplate;

    public void recordSuccess(Long webhookId, long responseTimeMs) {
        record(webhookId, true, responseTimeMs);
    }

    public void recordFailure(Long webhookId) {
        record(webhookId, false, null);
    }

    public DeliveryStats getStats() {
        try {
            return new DeliveryStats(counter("stats:delivery:success"), counter("stats:delivery:failed"));
        } catch (DataAccessException exception) {
            log.warn("Could not read global delivery statistics from Redis", exception);
            return new DeliveryStats(0, 0);
        }
    }

    public WebhookStats getWebhookStats(Long webhookId) {
        try {
            long successes = counter("stats:webhook:" + webhookId + ":success");
            long failures = counter("stats:webhook:" + webhookId + ":failed");
            ListOperations<String, String> lists = redisTemplate.opsForList();
            var recentLatencies = lists.range("stats:webhook:" + webhookId + ":latency", 0, -1);
            double averageLatencyMs = recentLatencies == null || recentLatencies.isEmpty()
                    ? 0
                    : recentLatencies.stream().mapToLong(Long::parseLong).average().orElse(0);
            return new WebhookStats(successes, failures, averageLatencyMs);
        } catch (DataAccessException exception) {
            log.warn("Could not read delivery statistics for webhook {} from Redis", webhookId, exception);
            return new WebhookStats(0, 0, 0);
        }
    }

    private long counter(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private void record(Long webhookId, boolean success, Long responseTimeMs) {
        String outcome = success ? "success" : "failed";
        try {
            redisTemplate.opsForValue().increment("stats:delivery:" + outcome);
            redisTemplate.opsForValue().increment("stats:webhook:" + webhookId + ":" + outcome);
            if (success && responseTimeMs != null) {
                String latencyKey = "stats:webhook:" + webhookId + ":latency";
                redisTemplate.opsForList().leftPush(latencyKey, Long.toString(Math.max(0, responseTimeMs)));
                redisTemplate.opsForList().trim(latencyKey, 0, LATENCY_HISTORY_SIZE - 1);
            }
        } catch (DataAccessException exception) {
            log.warn("Could not record {} delivery statistics for webhook {}; delivery state is unchanged",
                    outcome, webhookId, exception);
        }
    }

    public record DeliveryStats(long successCount, long failureCount) {
    }

    public record WebhookStats(long successCount, long failureCount, double averageLatencyMs) {
    }
}
