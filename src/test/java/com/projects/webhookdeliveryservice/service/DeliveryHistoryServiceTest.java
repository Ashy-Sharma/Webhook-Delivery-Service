package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryLog;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.Event;
import com.projects.webhookdeliveryservice.repository.DeliveryLogRepository;
import com.projects.webhookdeliveryservice.repository.DeliveryRepository;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryHistoryServiceTest {

    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final DeliveryLogRepository deliveryLogRepository = mock(DeliveryLogRepository.class);
    private final WebhookRepository webhookRepository = mock(WebhookRepository.class);
    private final DeliveryStatsService deliveryStatsService = mock(DeliveryStatsService.class);
    private final DeliveryHistoryService service = new DeliveryHistoryService(deliveryRepository,
            deliveryLogRepository, webhookRepository, deliveryStatsService, new ObjectMapper());

    @Test
    void historyCapsPageSizeAndBatchLoadsTheLatestLog() {
        Delivery delivery = delivery(8L);
        when(webhookRepository.existsByIdAndDeletedAtIsNull(4L)).thenReturn(true);
        when(deliveryRepository.findByWebhook_Id(eq(4L), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(delivery)));
        DeliveryLogRepository.LatestDeliveryLogProjection latest = mock(DeliveryLogRepository.LatestDeliveryLogProjection.class);
        when(latest.getDeliveryId()).thenReturn(8L);
        when(latest.getResponseStatus()).thenReturn(502);
        when(latest.getErrorMessage()).thenReturn("bad gateway");
        when(deliveryLogRepository.findLatestLogsForDeliveries(List.of(8L))).thenReturn(List.of(latest));

        var result = service.getWebhookDeliveries(4L, null, 0, 1_000_000);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).eventType()).isEqualTo("order.created");
        assertThat(result.content().get(0).lastAttemptStatus()).isEqualTo(502);
        assertThat(result.size()).isEqualTo(100);
        verify(deliveryRepository).findByWebhook_Id(eq(4L), org.mockito.ArgumentMatchers.argThat(pageable ->
                pageable.getPageSize() == 100 && pageable.getSort().isSorted()));
    }

    @Test
    void logsReturnBodyPreviewByDefaultAndFullBodyOnlyWhenRequested() {
        String body = "x".repeat(240);
        DeliveryLog log = DeliveryLog.builder().attemptNumber(0).requestHeaders("{}").requestBody(body)
                .responseStatus(null).responseTimeMs(null).createdAt(Instant.now()).build();
        when(deliveryRepository.existsById(8L)).thenReturn(true);
        when(deliveryLogRepository.findByDelivery_IdOrderByAttemptNumberAscCreatedAtAscIdAsc(8L))
                .thenReturn(List.of(log));

        var summary = service.getDeliveryLogs(8L, false).get(0);
        var detail = service.getDeliveryLogs(8L, true).get(0);

        assertThat(summary.bodyPreview()).hasSize(201).endsWith("…");
        assertThat(summary.requestBody()).isNull();
        assertThat(summary.responseStatus()).isNull();
        assertThat(summary.responseTimeMs()).isNull();
        assertThat(detail.requestBody()).isEqualTo(body);
    }

    private Delivery delivery(Long id) {
        Event event = Event.builder().id(9L).eventType("order.created").build();
        return Delivery.builder().id(id).event(event).status(DeliveryStatus.DELIVERED)
                .attemptCount(1).createdAt(Instant.now()).build();
    }
}
