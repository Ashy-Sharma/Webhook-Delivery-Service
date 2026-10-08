package com.projects.webhookdeliveryservice.dto.request;

import com.projects.webhookdeliveryservice.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.URL;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateWebhookRequest {

    @NotBlank
    @URL(message = "Must be a valid URL")
    @Length(max = 2048)
    private String url;

    @NotEmpty
    private List<String> eventTypes;

    @Length(max = 500)
    private String description;

}

