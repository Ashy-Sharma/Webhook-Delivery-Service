package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}


