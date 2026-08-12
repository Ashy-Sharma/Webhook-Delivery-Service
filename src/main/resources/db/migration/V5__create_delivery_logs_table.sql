create table delivery_logs (
    id bigint auto_increment primary key,
    delivery_id bigint not null,
    attempt_number int not null,
    request_url varchar(2048),
    request_headers json,
    request_body text,
    response_status int,
    response_body varchar(1024),
    response_time_ms bigint,
    error_message varchar(1000),
    created_at timestamp not null default current_timestamp,
    constraint fk_delivery_logs_delivery foreign key (delivery_id) references deliveries(id),
    index idx_delivery_logs_delivery_id (delivery_id)
);