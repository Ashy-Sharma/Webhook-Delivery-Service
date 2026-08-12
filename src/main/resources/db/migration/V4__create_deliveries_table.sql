create table deliveries (
    id bigint auto_increment primary key,
    webhook_id bigint not null,
    event_id bigint not null,
    status enum('PENDING', 'DELIVERING', 'DELIVERED', 'RETRYING', 'FAILED') not null default 'PENDING',
    attempt_count int not null default 0,
    max_attempts int not null default 5,
    reconciliation_attempts int not null default 0,
    next_retry_at timestamp null,
    delivered_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    constraint fk_deliveries_webhook foreign key (webhook_id) references webhooks(id),
    constraint fk_deliveries_event foreign key (event_id) references events(id),
    index idx_deliveries_webhook_id (webhook_id),
    index idx_deliveries_status (status),
    index idx_deliveries_status_created_at (status, created_at),
    index idx_deliveries_webhook_status (webhook_id, status)
);