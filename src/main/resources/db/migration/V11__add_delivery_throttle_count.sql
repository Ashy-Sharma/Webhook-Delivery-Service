alter table deliveries
    add column throttle_count int not null default 0;
