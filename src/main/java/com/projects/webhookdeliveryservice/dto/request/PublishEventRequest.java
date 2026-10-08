package com.projects.webhookdeliveryservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishEventRequest {

    @NotBlank
    @Size(max = 100)
    private String eventType;

    @NotNull
    private Map<String, Object> payload;

    @Size(max = 100)
    private String source;
}
