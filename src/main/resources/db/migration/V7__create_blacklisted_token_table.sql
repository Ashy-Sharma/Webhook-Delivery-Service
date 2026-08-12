create table blacklisted_tokens(
     id bigint auto_increment primary key,
     token_hash varchar(255) unique not null ,
     expires_at timestamp not null ,
     revoked_at timestamp default current_timestamp,

     index idx_blacklisted_tokens_token_hash (token_hash)
);