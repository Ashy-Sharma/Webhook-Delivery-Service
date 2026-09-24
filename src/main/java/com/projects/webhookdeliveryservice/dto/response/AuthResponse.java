package com.projects.webhookdeliveryservice.dto.response;

import lombok.*;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class AuthResponse {

    private String accessToken;

    private String refreshToken;

    @Builder.Default
    private String tokenType = "Bearer";

}
