create table refresh_tokens (
    id bigint auto_increment primary key,
    user_id bigint not null,
    token_hash varchar(255) not null unique,
    expires_at timestamp not null,
    is_revoked boolean not null default false,
    created_at timestamp not null default current_timestamp,

    foreign key (user_id) references users (id) on delete cascade,

    index idx_refresh_user (user_id),
    index idx_refresh_token (token_hash),
    index idx_refresh_expires (expires_at)
);