create table events (
    id bigint auto_increment primary key,
    event_type varchar(100) not null,
    payload json not null,
    source varchar(100),
    created_at timestamp not null default current_timestamp,
    index idx_events_event_type (event_type),
    index idx_events_created_at (created_at)
);