package com.projects.webhookdeliveryservice.repository;

import com.projects.webhookdeliveryservice.entity.RefreshToken;
import com.projects.webhookdeliveryservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken r set r.isRevoked = true where r.isRevoked = false and r.user = :user")
    void revokeAllByUser(@Param("user") User user);
}
