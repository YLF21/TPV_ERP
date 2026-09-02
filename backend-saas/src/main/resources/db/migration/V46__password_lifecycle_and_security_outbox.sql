alter table saas_admin_user
    add column must_change_password boolean not null default false;

alter table saas_tenant_user
    add column must_change_password boolean not null default false;

update saas_admin_user
set must_change_password = true
where lower(btrim(username)) = 'admin';

create table saas_password_reset_token (
    id uuid primary key,
    realm varchar(16) not null check (realm in ('admin', 'tenant')),
    username_key varchar(80) not null,
    token_hash varchar(64) not null unique,
    requested_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    consumed_at timestamp with time zone,
    requested_address varchar(128) not null
);

create index idx_saas_password_reset_lookup
    on saas_password_reset_token(token_hash, expires_at)
    where consumed_at is null;

create table saas_security_notification_outbox (
    id uuid primary key,
    event_type varchar(40) not null,
    realm varchar(16) not null,
    username_key varchar(80) not null,
    encrypted_payload text not null,
    status varchar(16) not null default 'PENDING',
    created_at timestamp with time zone not null,
    delivered_at timestamp with time zone,
    attempt_count integer not null default 0,
    last_error varchar(500)
);

create index idx_saas_security_outbox_pending
    on saas_security_notification_outbox(status, created_at);
