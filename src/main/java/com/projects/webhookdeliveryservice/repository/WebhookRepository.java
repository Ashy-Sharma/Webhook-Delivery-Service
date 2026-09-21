package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {
}
