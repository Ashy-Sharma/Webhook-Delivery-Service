package com.projects.webhookdeliveryservice.dto.request;

import com.projects.webhookdeliveryservice.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateWebhookRequest {

    @NotBlank
    @Length(max = 2048)
    private String url;

    @NotNull
    private List<String> eventType;

    private String description;

}

