-- Installation-authenticated repair commands. Payload fields are immutable after first acceptance.
create table store_remote_repair (
    command_id uuid primary key,
    installation_id uuid not null,
    saas_company_id uuid not null,
    saas_store_id uuid not null,
    event_id uuid not null,
    action varchar(64) not null,
    expected_version bigint not null check (expected_version >= 0),
    expires_at timestamptz not null,
    payload_fingerprint varchar(64) not null,
    local_company_id uuid,
    local_store_id uuid,
    status varchar(16) not null,
    result_code varchar(32) not null,
    reported_status varchar(16),
    reported_result_code varchar(32),
    created_at timestamptz not null,
    checked_at timestamptz not null,
    updated_at timestamptz not null,
    check ((local_company_id is null) = (local_store_id is null)),
    check ((status = 'RUNNING' and result_code = 'RETRY_QUEUED')
        or (status = 'SUCCEEDED' and result_code = 'SYNC_DELIVERED')
        or (status = 'FAILED' and result_code in ('STALE_EVENT','EVENT_NOT_FOUND',
            'UNSUPPORTED_ACTION','RETRY_FAILED','REPAIR_EXPIRED')))
);
create index ix_store_remote_repair_pending on store_remote_repair(installation_id, checked_at, command_id)
    where status = 'RUNNING' or reported_status is distinct from status or reported_result_code is distinct from result_code;
