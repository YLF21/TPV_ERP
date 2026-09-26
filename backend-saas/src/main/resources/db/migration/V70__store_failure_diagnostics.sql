-- Bounded technical identifiers only. No messages, stack traces or credentials.
alter table saas_store_failure
    add column module varchar(16),
    add column app_version varchar(80),
    add column trace_id varchar(128),
    add column exception_type varchar(160),
    add column error_location varchar(240);

alter table saas_store_failure drop constraint ck_saas_store_failure_source;
alter table saas_store_failure add constraint ck_saas_store_failure_source
    check (source in ('LOCAL_CONTROL', 'LOCAL_SYNC', 'LOCAL_APPLICATION', 'SYNC_PROJECTION'));
alter table saas_store_failure add constraint ck_saas_store_failure_module
    check (module is null or module in ('SALES', 'PRINTING', 'SYNC', 'APPLICATION'));

-- Keep each accepted revision: an older correlation ID must still find the
-- aggregate after another occurrence or an out-of-order upload arrives.
create table saas_store_failure_trace (
    failure_id uuid not null references saas_store_failure(id) on delete cascade,
    source_revision bigint not null check (source_revision >= 0),
    source_hash varchar(64) not null,
    trace_id varchar(128),
    primary key (failure_id, source_revision)
);
create index idx_saas_store_failure_trace_id on saas_store_failure_trace(trace_id);
insert into saas_store_failure_trace(failure_id, source_revision, source_hash, trace_id)
select id, source_revision, source_hash, trace_id
  from saas_store_failure where source_hash is not null;
