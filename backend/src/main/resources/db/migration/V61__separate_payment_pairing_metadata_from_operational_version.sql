alter table configuracion_pago_terminal
    add column operational_version bigint not null default 0;

update configuracion_pago_terminal
set operational_version = version;

-- Existing operations contain the pre-V61 fingerprint, which included pairing metadata.
-- Mark only those rows as legacy; every operation inserted after this migration is strict.
alter table payment_terminal_operation
    add column legacy_configuration_fingerprint boolean not null default true;

alter table payment_terminal_operation
    alter column legacy_configuration_fingerprint set default false;

-- A pre-V61 version drift cannot be classified safely as pairing-only versus financial.
-- Fail closed and require manual reconciliation instead of contacting the provider.
with candidates as materialized (
    select operation.id, operation.status as previous_status
    from payment_terminal_operation operation
    join configuracion_pago_terminal configuration
      on configuration.terminal_id = operation.terminal_id
    where operation.configuration_version <> configuration.operational_version
      and operation.status in ('PENDING', 'SENT', 'TIMEOUT')
), reviewed as (
    update payment_terminal_operation operation
    set status = 'REVIEW_REQUIRED',
        updated_at = current_timestamp,
        processing_owner = null,
        processing_lease_until = null,
        next_retry_at = null
    from candidates
    where operation.id = candidates.id
    returning operation.id
)
insert into payment_terminal_event (
    id, operation_id, previous_status, new_status, normalized_code, diagnostic, metadata, created_at)
select gen_random_uuid(), reviewed.id, candidates.previous_status, 'REVIEW_REQUIRED',
       'V61_LEGACY_VERSION_DRIFT',
       'Operacion heredada bloqueada: la version de configuracion requiere conciliacion manual',
       jsonb_build_object('migration', 'V61'), current_timestamp
from reviewed
join candidates on candidates.id = reviewed.id;
