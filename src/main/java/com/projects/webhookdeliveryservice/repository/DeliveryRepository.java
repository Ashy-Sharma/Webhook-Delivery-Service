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

    @EntityGraph(attributePaths = {"event"})
    List<Delivery> findByStatusAndCreatedAtBefore(DeliveryStatus status, Instant threshold);

    @Modifying
    @Query("UPDATE Delivery d SET d.status = :cancelled " +
            "WHERE d.webhook.id = :id AND d.status IN (:open)")
    int cancelOpenDeliveries(@Param("id") Long id,
                             @Param("cancelled") DeliveryStatus cancelled,
                             @Param("open") Collection<DeliveryStatus> open);

}
