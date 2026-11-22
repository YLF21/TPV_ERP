alter table saas_smtp_settings add column oauth_provider varchar(10)
    check (oauth_provider in ('GOOGLE','MICROSOFT'));
alter table saas_smtp_settings add constraint saas_oauth_sender check (
    oauth_provider is null or (encrypted_password is null and port=587 and security='STARTTLS'
    and username<>'' and username=from_address and host=case oauth_provider
        when 'GOOGLE' then 'smtp.gmail.com' when 'MICROSOFT' then 'smtp.office365.com' end));

-- Kept on a separate row so a rotated token can commit while the sender holds a settings share lock.
create table saas_mail_oauth_connection (
    singleton boolean primary key check (singleton),
    provider varchar(10) not null check (provider in ('GOOGLE','MICROSOFT')),
    email varchar(254) not null,
    access_token text not null,
    refresh_token text not null,
    expires_at timestamptz not null
);

create table saas_mail_oauth_flow (
    singleton boolean primary key check (singleton),
    state_hash char(64) not null,
    verifier text not null,
    provider varchar(10) not null check (provider in ('GOOGLE','MICROSOFT')),
    email varchar(254) not null,
    staff_id uuid not null references saas_admin_user(id) on delete cascade,
    settings_version bigint not null,
    enabled boolean not null,
    from_name varchar(120) not null,
    reply_to varchar(254) not null,
    portal_url varchar(1024) not null,
    expires_at timestamptz not null,
    consumed boolean not null default false
);
