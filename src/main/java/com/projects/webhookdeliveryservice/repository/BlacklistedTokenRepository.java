package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.BlacklistedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface BlacklistedTokenRepository extends JpaRepository<BlacklistedToken, Long> {
    boolean existsByTokenHash(String tokenHash);

    @Modifying
    @Transactional
    void deleteByExpiresAtBefore(Instant expiresAtBefore);
}
