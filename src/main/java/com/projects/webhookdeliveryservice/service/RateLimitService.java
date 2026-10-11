package com.projects.webhookdeliveryservice.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${webhook.ratelimit.max-per-minute:30}")
    private int maxPerWindow;

    @Value("${webhook.ratelimit.window-ms:60000}")
    private long windowMillis;

    @PostConstruct
    void validateConfig() {
        if (maxPerWindow < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("Rate limit and window must be positive");
        }
    }

    public RateLimitDecision checkAndRecord(Long webhookId) {
        long now = System.currentTimeMillis();
        String key = key(webhookId);
        try {
            ZSetOperations<String, String> sortedSets = redisTemplate.opsForZSet();
            sortedSets.removeRangeByScore(key, 0, now - windowMillis);
            Long count = sortedSets.size(key);
            long currentCount = count == null ? 0 : count;
            if (currentCount >= maxPerWindow) {
                return new RateLimitDecision(false, getWaitUntilNextSlotMillis(key, now), 0);
            }

            // Include a unique suffix so simultaneous requests in one millisecond
            // don't overwrite each other in the sorted set.
            sortedSets.add(key, now + ":" + UUID.randomUUID(), now);
            redisTemplate.expire(key, Duration.ofMillis(windowMillis * 2));
            return new RateLimitDecision(true, 0, Math.max(0, maxPerWindow - currentCount - 1));
        } catch (DataAccessException exception) {
            log.warn("Redis rate-limit check failed for webhook {}; allowing delivery", webhookId, exception);
            return new RateLimitDecision(true, 0, maxPerWindow);
        }
    }

    public long getWaitUntilNextSlotMillis(Long webhookId) {
        return getWaitUntilNextSlotMillis(key(webhookId), System.currentTimeMillis());
    }

    public long getCurrentUsage(Long webhookId) {
        long now = System.currentTimeMillis();
        try {
            Long count = redisTemplate.opsForZSet().count(key(webhookId), now - windowMillis, now);
            return count == null ? 0 : count;
        } catch (DataAccessException exception) {
            log.warn("Could not read rate-limit usage for webhook {}", webhookId, exception);
            return 0;
        }
    }

    public int getConfiguredLimit() {
        return maxPerWindow;
    }

    private long getWaitUntilNextSlotMillis(String key, long now) {
        try {
            Set<ZSetOperations.TypedTuple<String>> oldest = redisTemplate.opsForZSet()
                    .rangeWithScores(key, 0, 0);
            if (oldest == null || oldest.isEmpty()) {
                return 0;
            }
            Double oldestScore = oldest.iterator().next().getScore();
            if (oldestScore == null) {
                return 0;
            }
            return Math.max(0, (long) Math.ceil(oldestScore + windowMillis - now));
        } catch (DataAccessException exception) {
            log.warn("Could not estimate rate-limit wait for {}", key, exception);
            return windowMillis;
        }
    }

    private String key(Long webhookId) {
        return "ratelimit:webhook:" + webhookId;
    }

    public record RateLimitDecision(boolean allowed, long waitMillis, long remainingQuota) {
    }
}
