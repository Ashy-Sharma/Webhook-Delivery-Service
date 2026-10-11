package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.Delivery;
import com.projects.webhookdeliveryservice.entity.DeliveryStatus;
import com.projects.webhookdeliveryservice.entity.DeliveryFailureReason;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    long countByStatus(DeliveryStatus status);

    long countByStatusAndFailureReason(DeliveryStatus status, DeliveryFailureReason failureReason);

    long countByStatusAndFailureReasonIn(DeliveryStatus status, Collection<DeliveryFailureReason> failureReasons);

    long countByStatusAndReconciliationAttemptsGreaterThanEqual(DeliveryStatus status, int attempts);

    @EntityGraph(attributePaths = "event")
    Page<Delivery> findByWebhook_Id(Long webhookId, Pageable pageable);

    @EntityGraph(attributePaths = "event")
    Page<Delivery> findByWebhook_IdAndStatus(Long webhookId, DeliveryStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "event")
    List<Delivery> findTop10ByWebhook_IdOrderByCreatedAtDescIdDesc(Long webhookId);

    @Query(value = "SELECT COALESCE(AVG(response_time_ms), 0) FROM delivery_logs " +
            "WHERE response_status BETWEEN 200 AND 299 AND response_time_ms IS NOT NULL " +
            "AND attempt_number > 0 AND created_at >= :since", nativeQuery = true)
    Double averageSuccessfulLatencySince(@Param("since") Instant since);

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

    @EntityGraph(attributePaths = {"event", "webhook"})
    @Query("SELECT d FROM Delivery d WHERE d.id = :deliveryId")
    java.util.Optional<Delivery> findDetailedById(@Param("deliveryId") Long deliveryId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :pending, d.attemptCount = 0, " +
            "d.failureReason = null, d.nextRetryAt = null, d.claimedAt = null, " +
            "d.throttledSince = null, d.infrastructureDeferredSince = null, " +
            "d.deliveredAt = null, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :failed")
    int resetFailedDeliveryForManualRetry(@Param("deliveryId") Long deliveryId,
                                          @Param("failed") DeliveryStatus failed,
                                          @Param("pending") DeliveryStatus pending,
                                          @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.reconciliationAttempts = d.reconciliationAttempts + 1 " +
            "WHERE d.id = :deliveryId AND d.status = :pending " +
            "AND d.reconciliationAttempts < :maxAttempts")
    int bumpReconciliationAttempt(@Param("deliveryId") Long deliveryId,
                                  @Param("pending") DeliveryStatus pending,
                                  @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.infrastructureDeferredSince = COALESCE(" +
            "d.infrastructureDeferredSince, :deferredSince) WHERE d.id = :deliveryId")
    int setInfrastructureDeferredSince(@Param("deliveryId") Long deliveryId,
                                       @Param("deferredSince") Instant deferredSince);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :delivering, d.claimedAt = :claimedAt, " +
            "d.infrastructureDeferredSince = COALESCE(d.infrastructureDeferredSince, :deferredSince), " +
            "d.nextRetryAt = null, d.updatedAt = :claimedAt " +
            "WHERE d.id = :deliveryId AND d.webhook.id = :webhookId AND d.status IN (:claimable) " +
            "AND (d.status <> :retrying OR d.nextRetryAt IS NULL OR d.nextRetryAt <= :claimedAt) " +
            "AND :deferredSince <= :deadline " +
            "AND (d.infrastructureDeferredSince IS NULL OR d.infrastructureDeferredSince <= :deadline)")
    int claimExpiredInfrastructureDeferral(@Param("deliveryId") Long deliveryId,
                                           @Param("webhookId") Long webhookId,
                                           @Param("delivering") DeliveryStatus delivering,
                                           @Param("claimable") Collection<DeliveryStatus> claimable,
                                           @Param("retrying") DeliveryStatus retrying,
                                           @Param("claimedAt") Instant claimedAt,
                                           @Param("deferredSince") Instant deferredSince,
                                           @Param("deadline") Instant deadline);

    @Modifying
    @Query("UPDATE Delivery d SET d.status = :cancelled " +
            "WHERE d.webhook.id = :id AND d.status IN (:open)")
    int cancelOpenDeliveries(@Param("id") Long id,
                             @Param("cancelled") DeliveryStatus cancelled,
                             @Param("open") Collection<DeliveryStatus> open);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :delivering, d.claimedAt = :claimedAt, d.nextRetryAt = null, " +
            "d.attemptCount = d.attemptCount + 1 " +
            "WHERE d.id = :deliveryId AND d.status IN (:claimable) " +
            "AND (d.status <> :retrying OR d.nextRetryAt IS NULL OR d.nextRetryAt <= :claimedAt) " +
            "AND d.attemptCount < d.maxAttempts")
    int claimForDelivery(@Param("deliveryId") Long deliveryId,
                         @Param("delivering") DeliveryStatus delivering,
                         @Param("claimable") Collection<DeliveryStatus> claimable,
                         @Param("claimedAt") Instant claimedAt,
                         @Param("retrying") DeliveryStatus retrying);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :retrying, d.claimedAt = null, d.throttledSince = null, " +
            "d.failureReason = null, d.infrastructureDeferredSince = null, " +
            "d.nextRetryAt = :nextRetryAt, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :delivering AND d.claimedAt = :claimedAt")
    int scheduleRetry(@Param("deliveryId") Long deliveryId,
                      @Param("delivering") DeliveryStatus delivering,
                      @Param("claimedAt") Instant claimedAt,
                      @Param("retrying") DeliveryStatus retrying,
                      @Param("nextRetryAt") Instant nextRetryAt,
                      @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :retrying, d.failureReason = null, " +
            "d.attemptCount = d.attemptCount - 1, " +
            "d.throttleCount = d.throttleCount + 1, " +
            "d.throttledSince = COALESCE(d.throttledSince, :now), d.infrastructureDeferredSince = null, " +
            "d.claimedAt = null, " +
            "d.nextRetryAt = :nextRetryAt, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :delivering AND d.claimedAt = :claimedAt " +
            "AND d.attemptCount > 0 AND (d.throttledSince IS NULL OR d.throttledSince > :throttleDeadline)")
    int releaseClaimForThrottle(@Param("deliveryId") Long deliveryId,
                                @Param("delivering") DeliveryStatus delivering,
                                @Param("claimedAt") Instant claimedAt,
                                @Param("retrying") DeliveryStatus retrying,
                                @Param("nextRetryAt") Instant nextRetryAt,
                                @Param("now") Instant now,
                                @Param("throttleDeadline") Instant throttleDeadline);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :failed, d.failureReason = :failureReason, " +
            "d.attemptCount = d.attemptCount - 1, " +
            "d.throttleCount = d.throttleCount + 1, d.claimedAt = null, " +
            "d.nextRetryAt = null, d.deliveredAt = null, d.throttledSince = null, " +
            "d.infrastructureDeferredSince = null, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :delivering AND d.claimedAt = :claimedAt " +
            "AND d.attemptCount > 0 AND d.throttledSince IS NOT NULL " +
            "AND d.throttledSince <= :throttleDeadline")
    int failDeliveryAfterThrottleAge(@Param("deliveryId") Long deliveryId,
                                     @Param("delivering") DeliveryStatus delivering,
                                     @Param("claimedAt") Instant claimedAt,
                                     @Param("failed") DeliveryStatus failed,
                                     @Param("failureReason") DeliveryFailureReason failureReason,
                                     @Param("now") Instant now,
                                     @Param("throttleDeadline") Instant throttleDeadline);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :delivering, d.claimedAt = :claimedAt, " +
            "d.nextRetryAt = null, d.updatedAt = :claimedAt " +
            "WHERE d.id = :deliveryId AND d.webhook.id = :webhookId " +
            "AND d.status IN (:claimable) AND d.attemptCount >= d.maxAttempts")
    int claimExhaustedDeliveryForDlq(@Param("deliveryId") Long deliveryId,
                                    @Param("webhookId") Long webhookId,
                                    @Param("delivering") DeliveryStatus delivering,
                                    @Param("claimable") Collection<DeliveryStatus> claimable,
                                    @Param("claimedAt") Instant claimedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :failed, d.failureReason = :failureReason, " +
            "d.deliveredAt = null, d.throttledSince = null, d.infrastructureDeferredSince = null, " +
            "d.claimedAt = null, d.nextRetryAt = null, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status IN (:claimable)")
    int failUnclaimedDelivery(@Param("deliveryId") Long deliveryId,
                              @Param("claimable") Collection<DeliveryStatus> claimable,
                              @Param("failed") DeliveryStatus failed,
                              @Param("failureReason") DeliveryFailureReason failureReason,
                              @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :retrying, d.claimedAt = null, " +
            "d.throttledSince = null, d.failureReason = null, " +
            "d.nextRetryAt = :threshold, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :delivering AND d.claimedAt < :threshold")
    int releaseExpiredClaim(@Param("deliveryId") Long deliveryId,
                            @Param("delivering") DeliveryStatus delivering,
                            @Param("retrying") DeliveryStatus retrying,
                            @Param("threshold") Instant threshold,
                            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :retrying AND d.nextRetryAt < :threshold " +
            "AND d.updatedAt < :threshold")
    int rescheduleRetryDispatch(@Param("deliveryId") Long deliveryId,
                                @Param("retrying") DeliveryStatus retrying,
                                @Param("threshold") Instant threshold,
                                @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.status = :finalStatus, d.failureReason = :failureReason, " +
            "d.deliveredAt = :deliveredAt, d.claimedAt = null, d.throttledSince = null, " +
            "d.infrastructureDeferredSince = null, d.updatedAt = :now " +
            "WHERE d.id = :deliveryId AND d.status = :delivering AND d.claimedAt = :claimedAt")
    int finishClaimedDelivery(@Param("deliveryId") Long deliveryId,
                              @Param("delivering") DeliveryStatus delivering,
                              @Param("claimedAt") Instant claimedAt,
                              @Param("finalStatus") DeliveryStatus finalStatus,
                              @Param("failureReason") DeliveryFailureReason failureReason,
                              @Param("deliveredAt") Instant deliveredAt,
                              @Param("now") Instant now);

}
