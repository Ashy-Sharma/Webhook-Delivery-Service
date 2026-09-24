package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.entity.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {

    List<Webhook> findAllByOwner(User user);

    Boolean existsByIdAndOwner(Long id, User owner);

    void removeByIdAndOwner(Long id, User owner);

    Optional<Webhook> findByIdAndOwner(Long id, User owner);
}
