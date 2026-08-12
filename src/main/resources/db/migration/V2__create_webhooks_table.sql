create table webhooks (
    id bigint auto_increment primary key,
    owner_id bigint not null,
    url varchar(2048) not null,
    secret_key varchar(255) not null,
    event_types json not null,
    is_active boolean not null default true,
    description varchar(500),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    constraint fk_webhooks_owner foreign key (owner_id) references users(id),
    index idx_webhooks_owner_id (owner_id),
    index idx_webhooks_is_active (is_active)
);