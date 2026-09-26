create table saas_store_repair_command (
    command_id uuid primary key,
    request_id uuid not null unique,
    failure_id uuid not null references saas_store_failure(id),
    failure_key varchar(90) not null,
    company_id uuid not null references saas_company(id),
    store_id uuid not null references saas_store(id),
    installation_id uuid not null references saas_installation(id),
    action varchar(40) not null check (action = 'RETRY_SYNC_OUTBOX'),
    event_id uuid not null,
    expected_version bigint not null check (expected_version >= 0),
    status varchar(16) not null check (status in ('QUEUED','RUNNING','SUCCEEDED','FAILED','EXPIRED')),
    result_code varchar(40),
    requested_by varchar(80) not null,
    reason varchar(500) not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    updated_at timestamptz not null,
    check (expires_at > created_at),
    check ((status = 'QUEUED' and result_code is null)
        or (status = 'RUNNING' and (result_code is null or result_code = 'RETRY_QUEUED'))
        or (status = 'SUCCEEDED' and result_code = 'SYNC_DELIVERED')
        or (status = 'FAILED' and result_code in ('STALE_EVENT','EVENT_NOT_FOUND','UNSUPPORTED_ACTION','RETRY_FAILED','REPAIR_EXPIRED'))
        or (status = 'EXPIRED' and result_code = 'REPAIR_EXPIRED'))
);
create unique index uq_saas_store_repair_active on saas_store_repair_command(failure_id)
    where status in ('QUEUED','RUNNING');
create index idx_saas_store_repair_installation on saas_store_repair_command(installation_id, status, created_at, command_id);
create index idx_saas_store_repair_failure on saas_store_repair_command(failure_id, created_at desc);

create table saas_store_failure_manual (
    failure_key varchar(90) primary key,
    company_id uuid not null references saas_company(id),
    ticket_id uuid not null references saas_support_ticket(id),
    requested_by varchar(80) not null,
    reason varchar(500) not null,
    created_at timestamptz not null
);

-- Optional retry key: legacy comments keep their previous append-only behavior.
alter table saas_support_ticket_comment add column client_request_id uuid;
create unique index uq_saas_support_comment_request on saas_support_ticket_comment(ticket_id, client_request_id)
    where client_request_id is not null;
