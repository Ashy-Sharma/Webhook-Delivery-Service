package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.entity.BlacklistedToken;
import com.projects.webhookdeliveryservice.repository.BlacklistedTokenRepository;
import com.projects.webhookdeliveryservice.security.JwtService;
import com.projects.webhookdeliveryservice.util.HashUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class BlacklistedTokenService {

    private final JwtService jwtService;

    private final BlacklistedTokenRepository blacklistedTokenRepository;

    @Transactional
    public void blacklist(String rawToken){
        String hashedToken = HashUtil.sha256(rawToken);
        Instant expiry = jwtService.extractExpiration(rawToken);
        if (expiry.isBefore(Instant.now()) || blacklistedTokenRepository.existsByTokenHash(hashedToken)){
            return;
        }
        BlacklistedToken newToken = BlacklistedToken.builder()
                .tokenHash(hashedToken)
                .expiresAt(expiry)
                .revoked_at(Instant.now())
                .build();
        blacklistedTokenRepository.save(newToken);
    }

    public boolean isBlacklisted(String rawToken){
        String hashedToken = HashUtil.sha256(rawToken);
        return blacklistedTokenRepository.existsByTokenHash(hashedToken);
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupExpired(){
        blacklistedTokenRepository.deleteByExpiresAtBefore(Instant.now());
    }


}
