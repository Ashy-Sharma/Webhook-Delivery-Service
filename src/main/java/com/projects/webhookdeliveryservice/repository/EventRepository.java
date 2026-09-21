package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.Event;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}
