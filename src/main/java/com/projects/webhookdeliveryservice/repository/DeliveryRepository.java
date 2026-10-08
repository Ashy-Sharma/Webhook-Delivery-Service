package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    // Use the database clock here so the MySQL TIMESTAMP comparison does not
    // depend on converting an application Instant into the connection timezone.
    @Query(value = "SELECT * FROM deliveries " +
            "WHERE status = 'PENDING' " +
            "AND created_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 2 MINUTE)",
            nativeQuery = true)
    List<Delivery> findStalePendingDeliveries();

    @EntityGraph(attributePaths = {"event", "webhook"})
    List<Delivery> findByStatusAndNextRetryAtBefore(DeliveryStatus status, Instant threshold);

    @EntityGraph(attributePaths = {"event", "webhook"})
    List<Delivery> findByStatusAndClaimedAtBefore(DeliveryStatus status, Instant threshold);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.reconciliationAttempts = d.reconciliationAttempts + 1 " +
            "WHERE d.id = :deliveryId AND d.status = :pending " +
            "AND d.reconciliationAttempts < :maxAttempts")
    int bumpReconciliationAttempt(@Param("deliveryId") Long deliveryId,
                                  @Param("pending") DeliveryStatus pending,
                                  @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("UPDATE Delivery d SET d.status = :cancelled " +
            "WHERE d.webhook.id = :id AND d.status IN (:open)")
    int cancelOpenDeliveries(@Param("id") Long id,
                             @Param("cancelled") DeliveryStatus cancelled,
                             @Param("open") Collection<DeliveryStatus> open);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :delivering, d.claimedAt = :claimedAt, d.nextRetryAt = null, " +
            "d.attemptCount = d.attemptCount + 1 " +
            "WHERE d.id = :deliveryId AND d.status IN (:claimable) AND d.attemptCount < d.maxAttempts")
    int claimForDelivery(@Param("deliveryId") Long deliveryId,
                         @Param("delivering") DeliveryStatus delivering,
                         @Param("claimable") Collection<DeliveryStatus> claimable,
                         @Param("claimedAt") Instant claimedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :failed, d.deliveredAt = null, " +
            "d.claimedAt = null, d.nextRetryAt = null, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status IN (:claimable) " +
            "AND d.attemptCount >= d.maxAttempts")
    int failExhaustedDelivery(@Param("deliveryId") Long deliveryId,
                              @Param("claimable") Collection<DeliveryStatus> claimable,
                              @Param("failed") DeliveryStatus failed,
                              @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :failed, d.deliveredAt = null, " +
            "d.claimedAt = null, d.nextRetryAt = null, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status IN (:claimable)")
    int failUnclaimedDelivery(@Param("deliveryId") Long deliveryId,
                              @Param("claimable") Collection<DeliveryStatus> claimable,
                              @Param("failed") DeliveryStatus failed,
                              @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :retrying, d.claimedAt = null, " +
            "d.nextRetryAt = :nextDispatchAt, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :delivering AND d.claimedAt < :threshold")
    int releaseExpiredClaim(@Param("deliveryId") Long deliveryId,
                            @Param("delivering") DeliveryStatus delivering,
                            @Param("retrying") DeliveryStatus retrying,
                            @Param("threshold") Instant threshold,
                            @Param("nextDispatchAt") Instant nextDispatchAt,
                            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.nextRetryAt = :nextDispatchAt, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :retrying AND d.nextRetryAt < :threshold")
    int rescheduleRetryDispatch(@Param("deliveryId") Long deliveryId,
                                @Param("retrying") DeliveryStatus retrying,
                                @Param("threshold") Instant threshold,
                                @Param("nextDispatchAt") Instant nextDispatchAt,
                                @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :finalStatus, d.deliveredAt = :deliveredAt, " +
            "d.claimedAt = null, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :delivering AND d.claimedAt = :claimedAt")
    int finishClaimedDelivery(@Param("deliveryId") Long deliveryId,
                              @Param("delivering") DeliveryStatus delivering,
                              @Param("claimedAt") Instant claimedAt,
                              @Param("finalStatus") DeliveryStatus finalStatus,
                              @Param("deliveredAt") Instant deliveredAt,
                              @Param("now") Instant now);

}
