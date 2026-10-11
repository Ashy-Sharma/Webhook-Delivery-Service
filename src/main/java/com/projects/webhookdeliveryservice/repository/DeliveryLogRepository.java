package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.DeliveryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {

    List<DeliveryLog> findByDelivery_IdOrderByAttemptNumberAscCreatedAtAscIdAsc(Long deliveryId);

    @Query(value = "SELECT candidate.delivery_id AS deliveryId, candidate.attempt_number AS attemptNumber, " +
            "candidate.response_status AS responseStatus, candidate.error_message AS errorMessage, " +
            "candidate.response_time_ms AS responseTimeMs " +
            "FROM delivery_logs candidate " +
            "WHERE candidate.delivery_id IN (:deliveryIds) AND NOT EXISTS (" +
            "SELECT 1 FROM delivery_logs newer WHERE newer.delivery_id = candidate.delivery_id " +
            "AND (newer.created_at > candidate.created_at OR " +
            "(newer.created_at = candidate.created_at AND newer.id > candidate.id)))",
            nativeQuery = true)
    List<LatestDeliveryLogProjection> findLatestLogsForDeliveries(@Param("deliveryIds") List<Long> deliveryIds);

    @Query(value = "SELECT d.webhook_id AS webhookId, MAX(log.created_at) AS lastDeliveryAt " +
            "FROM delivery_logs log JOIN deliveries d ON d.id = log.delivery_id " +
            "WHERE d.webhook_id IN (:webhookIds) GROUP BY d.webhook_id", nativeQuery = true)
    List<WebhookLastDeliveryProjection> findLastDeliveryTimesForWebhooks(
            @Param("webhookIds") List<Long> webhookIds);

    interface LatestDeliveryLogProjection {
        Long getDeliveryId();
        Integer getAttemptNumber();
        Integer getResponseStatus();
        String getErrorMessage();
        Long getResponseTimeMs();
    }

    interface WebhookLastDeliveryProjection {
        Long getWebhookId();
        java.time.Instant getLastDeliveryAt();
    }
}
