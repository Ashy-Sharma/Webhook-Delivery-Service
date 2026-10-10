package com.projects.webhookdeliveryservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    @Test
    void allowsDeliveryWhenRedisThrowsRuntimeException() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> sortedSets = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(sortedSets);
        doThrow(new RuntimeException("Redis command timed out"))
                .when(sortedSets).removeRangeByScore(eq("ratelimit:webhook:7"), anyDouble(), anyDouble());
        RateLimitService service = service(redisTemplate);

        RateLimitService.RateLimitDecision decision = service.checkAndRecord(7L);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingQuota()).isEqualTo(30);
    }

    @Test
    void usesUniqueSortedSetMembersForEachDelivery() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> sortedSets = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(sortedSets);
        when(sortedSets.size("ratelimit:webhook:7")).thenReturn(0L, 1L);
        when(sortedSets.add(eq("ratelimit:webhook:7"), anyString(), anyDouble())).thenReturn(true);
        RateLimitService service = service(redisTemplate);

        service.checkAndRecord(7L);
        service.checkAndRecord(7L);

        org.mockito.ArgumentCaptor<String> members = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sortedSets, org.mockito.Mockito.times(2))
                .add(eq("ratelimit:webhook:7"), members.capture(), anyDouble());
        List<String> values = new ArrayList<>(members.getAllValues());
        assertThat(values).hasSize(2).doesNotHaveDuplicates();
        assertThat(values).allMatch(value -> value.matches("\\d+:[0-9a-fA-F-]{36}"));
    }

    private RateLimitService service(StringRedisTemplate redisTemplate) {
        RateLimitService service = new RateLimitService(redisTemplate);
        ReflectionTestUtils.setField(service, "maxPerWindow", 30);
        ReflectionTestUtils.setField(service, "windowMillis", 60_000L);
        return service;
    }
}
