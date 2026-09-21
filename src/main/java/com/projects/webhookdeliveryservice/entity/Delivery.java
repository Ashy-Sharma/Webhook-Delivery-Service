package com.projects.webhookdeliveryservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Getter
@Builder
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Builder.Default
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Builder.Default
    @Column(name = "reconciliation_attempts", nullable = false)
    private int reconciliationAttempts = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "delivered_At")
    private Instant deliveredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne
    @JoinColumn(name = "webhook_id", nullable = false)
    private Webhook webhook;

    @ManyToOne
    @JoinColumn(name = "event_id")
    private Event event;

}
