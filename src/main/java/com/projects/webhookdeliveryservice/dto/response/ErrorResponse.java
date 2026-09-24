package com.projects.webhookdeliveryservice.dto.response;

import lombok.*;

import java.time.Instant;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ErrorResponse {

    private int status;
    private String error;
    private String message;
    private String path;
    private Instant timestamp;

}