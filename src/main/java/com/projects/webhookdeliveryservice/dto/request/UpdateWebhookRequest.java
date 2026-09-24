package com.projects.webhookdeliveryservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.URL;

import java.util.List;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class UpdateWebhookRequest {

    @URL(message = "Must be a valid URL")
    @Length(max = 2048)
    private String url;

    private List<String> eventType;

    @Length(max = 500)
    private String description;

    private Boolean isActive;

}
