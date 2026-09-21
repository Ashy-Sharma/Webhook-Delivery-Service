package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.DeliveryLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {
}
