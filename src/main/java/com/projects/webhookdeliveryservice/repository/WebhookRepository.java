package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.entity.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {


    Optional<Webhook> findByIdAndOwnerAndDeletedAtIsNull(Long id, User owner);

    @Query(value = "SELECT * FROM webhooks WHERE is_active = true " +
            "AND JSON_CONTAINS(event_types, JSON_QUOTE(:eventType))", nativeQuery = true)
    List<Webhook> findActiveWebhooksByEventType(@Param("eventType") String eventType);

    Collection<Webhook> findByOwnerAndDeletedAtIsNull(User user);
}