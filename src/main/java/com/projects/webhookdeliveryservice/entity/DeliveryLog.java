package com.projects.webhookdeliveryservice.entity;


import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Builder
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "delivery_logs")
public class DeliveryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "request_url", length = 2048)
    private String requestUrl;

    @Column(name = "request_headers", columnDefinition = "json")
    private String requestHeaders;

    @Column(name = "request_body")
    private String requestBody;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", length = 1024)
    private String responseBody;

    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

}

