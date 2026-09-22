-- Retain evidence even when a retry succeeds before the supervision scan.
alter table sync_outbox
    add column first_failure_at timestamptz,
    add column last_failure_at timestamptz,
    add column failure_count bigint not null default 0,
    add constraint ck_sync_outbox_failure_count check (failure_count >= 0);

update sync_outbox set first_failure_at = actualizado_en, last_failure_at = actualizado_en,
    failure_count = greatest(intentos, 1)
where estado in ('ERROR','DEAD_LETTER');

-- Source/revision lookups for durable reporter deduplication; commercial rows
-- are excluded from the index and their payloads are never inspected/reported.
create index ix_sync_outbox_supervision_source
    on sync_outbox(tienda_id, entidad_id, (payload ->> 'source'), ((payload ->> 'sourceRevision')::bigint) desc)
    where tipo_entidad = 'STORE_FAILURE';
create index ix_sync_outbox_failed_source
    on sync_outbox(tienda_id, actualizado_en, event_id)
    where failure_count > 0 and tipo_entidad <> 'STORE_FAILURE';
