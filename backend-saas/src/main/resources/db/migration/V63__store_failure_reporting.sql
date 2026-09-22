-- Received operational signals only. Payloads, credentials and customer data are
-- deliberately excluded; source identifiers allow authorised local diagnosis.
create table saas_store_failure (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    store_id uuid references saas_store(id),
    installation_id uuid references saas_installation(id),
    source varchar(32) not null,
    source_id uuid not null,
    source_revision bigint not null,
    source_hash varchar(64),
    status varchar(24) not null,
    severity varchar(16) not null,
    code varchar(80) not null,
    first_seen_at timestamp with time zone not null,
    last_seen_at timestamp with time zone not null,
    received_at timestamp with time zone not null,
    occurrences bigint not null,
    last_event_id uuid references saas_sync_event(event_id),
    constraint ck_saas_store_failure_source check (source in ('LOCAL_CONTROL','LOCAL_SYNC','SYNC_PROJECTION')),
    constraint ck_saas_store_failure_status check (status in ('OPEN','REVIEWED','RESOLVED','DISMISSED')),
    constraint ck_saas_store_failure_severity check (severity in ('INFO','WARNING','DANGER')),
    constraint ck_saas_store_failure_dates check (last_seen_at >= first_seen_at),
    constraint ck_saas_store_failure_count check (occurrences > 0 and source_revision >= 0),
    constraint uq_saas_store_failure_source unique (installation_id, source, source_id)
);

create index idx_saas_store_failure_recent on saas_store_failure(last_seen_at desc, id);
create index idx_saas_store_failure_store on saas_store_failure(store_id, status, last_seen_at desc, id);
create index idx_saas_store_failure_company on saas_store_failure(company_id, last_seen_at desc, id);

-- Existing persisted projection errors are genuine received failures. Their
-- original retry count/last attempt was not stored, so only one known occurrence
-- and the event reception timestamp can be reconstructed.
insert into saas_store_failure
    (id, company_id, store_id, installation_id, source, source_id, source_revision,
     status, severity, code, first_seen_at, last_seen_at, received_at, occurrences, last_event_id)
select event_id, company_id, store_id, installation_id, 'SYNC_PROJECTION', event_id, 0,
       'OPEN', 'DANGER', 'PROJECTION_FAILED', received_at, received_at, received_at, 1, event_id
  from saas_sync_event where projection_status = 'ERROR';
