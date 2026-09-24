package com.projects.webhookdeliveryservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.expiration-ms}")
    private long jwtExpiration;

    public String generateToken(UserDetails userDetails){
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", userDetails.getAuthorities().iterator().next().getAuthority());
        return generateToken(claims, userDetails);
    }

    public String generateToken(Map<String, Object> claims, UserDetails userDetails){
        return Jwts.builder()
                .claims(claims)
                .subject(userDetails.getUsername())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusMillis(jwtExpiration)))
                .signWith(getSigningKey())
                .compact();
    }


    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private Claims extractAllClaims(String token){
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }

    public <T> T extractClaims(String token, Function<Claims, T> claimsFunction){
        Claims claims = extractAllClaims(token);
        return claimsFunction.apply(claims);
    }

    public String extractUsername(String token){
        return extractClaims(token, Claims::getSubject);
    }

    public Instant extractExpiration(String token){
        return extractClaims(token, Claims::getExpiration).toInstant();
    }

    public boolean isTokenExpired(String token){
        return extractExpiration(token).isBefore(Instant.now());
    }

    public boolean isTokenValid(String token, UserDetails userDetails){
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public String extractRole(String token){
        return extractClaims(token, claims -> claims.get("role", String.class));
    }

}
