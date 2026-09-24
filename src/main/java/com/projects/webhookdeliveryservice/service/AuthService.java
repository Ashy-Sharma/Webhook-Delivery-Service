package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.dto.request.LoginRequest;
import com.projects.webhookdeliveryservice.dto.request.RefreshTokenRequest;
import com.projects.webhookdeliveryservice.dto.request.RegisterRequest;
import com.projects.webhookdeliveryservice.dto.response.AuthResponse;
import com.projects.webhookdeliveryservice.entity.RefreshToken;
import com.projects.webhookdeliveryservice.entity.Role;
import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.exception.DuplicateResourceException;
import com.projects.webhookdeliveryservice.repository.UserRepository;
import com.projects.webhookdeliveryservice.security.JwtService;
import com.projects.webhookdeliveryservice.security.RefreshTokenService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final BlacklistedTokenService blacklistedTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request){
        if(userRepository.existsByEmail(request.getEmail())){
            throw new DuplicateResourceException("Email already exists.");
        }
        if(userRepository.existsByUserName(request.getUsername())){
            throw new DuplicateResourceException("Username already exists.");
        }

        User user = User.builder()
                .email(request.getEmail())
                .userName(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .build();

        User savedUser = userRepository.save(user);

        String accessToken = jwtService.generateToken(savedUser);
        String refreshToken = refreshTokenService.createRefreshToken(savedUser);

        return AuthResponse.builder()
                .refreshToken(refreshToken)
                .accessToken(accessToken)
                .build();
    }

    public AuthResponse login(LoginRequest request){
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        User user = (User) authentication.getPrincipal();

        String accessToken = jwtService.generateToken(user);
        String refreshToken = refreshTokenService.createRefreshToken(user);

        return AuthResponse.builder()
                .refreshToken(refreshToken)
                .accessToken(accessToken)
                .build();
    }

    public AuthResponse refresh(RefreshTokenRequest request){
        RefreshToken existingToken = refreshTokenService.verify(request.getRefreshToken());
        User user = existingToken.getUser();

        String newAccessToken = jwtService.generateToken(user);
        String newRefreshToken = refreshTokenService.rotate(existingToken);

        return AuthResponse.builder()
                .refreshToken(newRefreshToken)
                .accessToken(newAccessToken)
                .build();
    }

    @Transactional
    public void logout(String rawRefreshToken, String accessToken){
        refreshTokenService.revoke(rawRefreshToken);
        blacklistedTokenService.blacklist(accessToken);
    }

    @Transactional
    public void logoutAll(User user, String accessToken){
        refreshTokenService.revokeAll(user);
        blacklistedTokenService.blacklist(accessToken);
    }

}
