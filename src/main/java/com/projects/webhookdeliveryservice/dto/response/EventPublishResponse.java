package com.projects.webhookdeliveryservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventPublishResponse {

    private Long eventId;
    private String eventType;
    private int webhooksMatched;
    private int deliveriesCreated;
}
