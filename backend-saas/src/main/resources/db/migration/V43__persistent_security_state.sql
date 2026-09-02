create table saas_session (
    token_hash varchar(64) primary key,
    realm varchar(16) not null,
    username varchar(80) not null,
    issued_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    constraint ck_saas_session_realm check (realm in ('admin', 'tenant'))
);

create index idx_saas_session_user on saas_session(realm, lower(username));
create index idx_saas_session_expiry on saas_session(expires_at);

create table saas_login_attempt (
    scope varchar(64) not null,
    username_key varchar(160) not null,
    remote_address varchar(64) not null,
    failures integer not null,
    last_failure_at timestamp with time zone not null,
    blocked_until timestamp with time zone,
    primary key(scope, username_key, remote_address),
    constraint ck_saas_login_attempt_failures check (failures > 0)
);

create index idx_saas_login_attempt_expiry on saas_login_attempt(last_failure_at, blocked_until);
