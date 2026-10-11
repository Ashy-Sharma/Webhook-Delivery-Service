package com.projects.webhookdeliveryservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

class DeliveryStatsServiceTest {

    @Test
    void recordsTerminalSuccessAndKeepsOnlyOneHundredLatencies() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked") ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForList()).thenReturn(lists);
        DeliveryStatsService service = new DeliveryStatsService(redis);

        service.recordSuccess(42L, 125);

        verify(values).increment("stats:delivery:success");
        verify(values).increment("stats:webhook:42:success");
        verify(lists).leftPush("stats:webhook:42:latency", "125");
        verify(lists).trim("stats:webhook:42:latency", 0, 99);
    }

    @Test
    void recordsOnlyFailureCountersForTerminalFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        DeliveryStatsService service = new DeliveryStatsService(redis);

        service.recordFailure(42L);

        verify(values).increment("stats:delivery:failed");
        verify(values).increment("stats:webhook:42:failed");
    }

    @Test
    void redisFailureDoesNotEscapeIntoDeliveryFlow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new DataAccessResourceFailureException("Redis unavailable"))
                .when(values).increment("stats:delivery:failed");
        DeliveryStatsService service = new DeliveryStatsService(redis);

        service.recordFailure(42L);
    }

    @Test
    void readsCountersAndAverageOfTheBoundedLatencyHistory() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked") ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForList()).thenReturn(lists);
        when(values.get("stats:webhook:42:success")).thenReturn("4");
        when(values.get("stats:webhook:42:failed")).thenReturn("2");
        when(lists.range("stats:webhook:42:latency", 0, -1)).thenReturn(java.util.List.of("100", "200", "300"));
        DeliveryStatsService service = new DeliveryStatsService(redis);

        var stats = service.getWebhookStats(42L);

        assertThat(stats.successCount()).isEqualTo(4);
        assertThat(stats.failureCount()).isEqualTo(2);
        assertThat(stats.averageLatencyMs()).isEqualTo(200);
    }
}
