alter table deliveries
    add column claimed_at timestamp null,
    add index idx_deliveries_status_claimed_at (status, claimed_at);
