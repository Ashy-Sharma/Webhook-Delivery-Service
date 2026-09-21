package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {
}
