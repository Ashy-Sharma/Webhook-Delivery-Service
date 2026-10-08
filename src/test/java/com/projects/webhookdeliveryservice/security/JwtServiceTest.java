package com.projects.webhookdeliveryservice.security;

import com.projects.webhookdeliveryservice.entity.Role;
import com.projects.webhookdeliveryservice.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey",
                Base64.getEncoder().encodeToString(new byte[32]));
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 900_000L);

        user = User.builder()
                .email("jwt-test@example.com")
                .userName("jwt_test")
                .passwordHash("not-used-in-this-test")
                .role(Role.USER)
                .build();
    }

    @Test
    void generatesDistinctValidTokensForSameUser() {
        String firstToken = jwtService.generateToken(user);
        String secondToken = jwtService.generateToken(user);

        assertNotEquals(firstToken, secondToken,
                "Each access token must have a unique JWT ID, even when issued in the same second");
        assertTrue(jwtService.isTokenValid(firstToken, user));
        assertTrue(jwtService.isTokenValid(secondToken, user));
    }
}
