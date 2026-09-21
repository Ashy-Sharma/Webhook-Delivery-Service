package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.BlacklistedToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlacklistedTokenRepository extends JpaRepository<BlacklistedToken, Long> {
}
