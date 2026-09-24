package com.projects.webhookdeliveryservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class WebhookResponse {

    private Long id;
    private String url;
    private List<String> eventType;
    private String secretKey;
    private String description;
    private Boolean isActive;
    private Instant createdAt;

}

