package com.projects.webhookdeliveryservice.security;

import com.projects.webhookdeliveryservice.entity.RefreshToken;
import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.exception.InvalidTokenException;
import com.projects.webhookdeliveryservice.repository.RefreshTokenRepository;
import com.projects.webhookdeliveryservice.util.HashUtil;
import com.projects.webhookdeliveryservice.util.TokenGenerator;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-expiration-ms}")
    private long refreshExpiration;

    public String createRefreshToken(User user){

        String rawToken = TokenGenerator.generateToken();
        String tokenHash = HashUtil.sha256(rawToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusMillis(refreshExpiration))
                .isRevoked(false)
                .build();

        refreshTokenRepository.save(refreshToken);
        return rawToken;

    }

    public RefreshToken verify(String rawToken){

        String tokenHash = HashUtil.sha256(rawToken);

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token!"));

        if (refreshToken.getExpiresAt().isBefore(Instant.now())){
            throw new InvalidTokenException("Token has expired");
        }

        if(refreshToken.isRevoked()){
            throw new InvalidTokenException("Token has been revoked. Invalid token");
        }

        return refreshToken;
    }

    @Transactional
    public void revoke(String rawToken){
        String tokenHash = HashUtil.sha256(rawToken);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token!"));
        refreshToken.setRevoked(true);
    }

    @Transactional
    public void revokeAll(User user){
        refreshTokenRepository.revokeAllByUser(user);
    }

    @Transactional
    public String rotate(RefreshToken refreshToken){

        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);
        return createRefreshToken(refreshToken.getUser());

    }

}
