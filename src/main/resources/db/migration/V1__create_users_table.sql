create table users (
    id bigint auto_increment primary key,
    email varchar(255) not null,
    username varchar(100) not null,
    password_hash varchar(255) not null,
    role varchar(20) not null default 'USER',
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    constraint uk_users_email unique (email),
    constraint uk_users_username unique (username)
);