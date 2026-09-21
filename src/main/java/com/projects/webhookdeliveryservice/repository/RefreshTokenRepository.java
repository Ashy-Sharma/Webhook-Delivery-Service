package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
}
