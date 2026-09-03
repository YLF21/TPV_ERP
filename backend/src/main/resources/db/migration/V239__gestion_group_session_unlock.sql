alter table usuario
    add column auth_version bigint not null default 0;

create table gestion_group_unlock_state (
    id uuid primary key,
    session_id uuid not null references sesion(id) on delete cascade,
    group_code varchar(32) not null,
    failed_attempts integer not null default 0,
    attempt_window_started_at timestamptz,
    blocked_until timestamptz,
    unlocked_at timestamptz,
    user_auth_version bigint,
    role_id uuid references rol(id) on delete set null,
    version bigint not null default 0,
    constraint ck_gestion_group_unlock_group
        check (group_code in ('FISCAL', 'SEGURIDAD', 'CONFIGURACION')),
    constraint ck_gestion_group_unlock_failed_attempts
        check (failed_attempts >= 0),
    constraint uq_gestion_group_unlock_session_group
        unique (session_id, group_code)
);

create index idx_gestion_group_unlock_session
    on gestion_group_unlock_state (session_id);
