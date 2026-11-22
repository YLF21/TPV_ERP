alter table saas_client_access_request add column locale varchar(2) not null default 'es'
    check (locale in ('es','en','zh'));

create table saas_smtp_settings (
    singleton boolean primary key check (singleton),
    enabled boolean not null default false,
    host varchar(253) not null default '',
    port integer not null default 587 check (port between 1 and 65535),
    security varchar(10) not null default 'STARTTLS' check (security in ('STARTTLS','SSL')),
    username varchar(254) not null default '',
    encrypted_password text,
    from_address varchar(254) not null default '',
    from_name varchar(120) not null default 'esPOS',
    reply_to varchar(254) not null default '',
    portal_url varchar(1024) not null default '',
    version bigint not null default 0,
    last_test_at timestamptz
);
insert into saas_smtp_settings(singleton) values (true);

-- Only the review transaction creates an entry. Prior decisions are never mailed retroactively.
create table saas_client_decision_email (
    request_id uuid primary key references saas_client_access_request(id),
    has_stores boolean not null,
    status varchar(12) not null default 'PENDING' check (status in ('PENDING','PROCESSING','DELIVERED','FAILED')),
    attempts integer not null default 0 check (attempts >= 0),
    created_at timestamptz not null default current_timestamp,
    next_attempt_at timestamptz,
    claim_token uuid,
    claimed_at timestamptz,
    delivered_at timestamptz,
    last_error varchar(40),
    check ((status = 'PROCESSING') = (claim_token is not null)),
    check ((status = 'PROCESSING') = (claimed_at is not null)),
    check ((status = 'DELIVERED') = (delivered_at is not null))
);
create index idx_client_decision_email_due on saas_client_decision_email (created_at,request_id)
    where status in ('PENDING','PROCESSING');
