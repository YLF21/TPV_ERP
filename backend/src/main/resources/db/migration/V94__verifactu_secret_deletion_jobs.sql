create table verifactu_secret_deletion_job (
    id uuid primary key,
    company_id uuid not null references empresa(id),
    certificate_id uuid references certificado_verifactu(id),
    secret_path varchar(512) not null,
    reason varchar(32) not null,
    status varchar(16) not null default 'PENDIENTE',
    attempts integer not null default 0,
    next_attempt_at timestamptz,
    processing_owner uuid,
    processing_lease_until timestamptz,
    last_error varchar(128),
    created_at timestamptz not null,
    completed_at timestamptz,
    version bigint not null default 0,
    constraint uq_verifactu_secret_deletion_path unique (secret_path),
    constraint ck_verifactu_secret_deletion_reason check (
        reason in ('ACTIVE_DELETED', 'PREVIOUS_REPLACED', 'RETENTION_PURGE',
                   'IMPORT_ROLLBACK', 'MIGRATION_RECONCILIATION')),
    constraint ck_verifactu_secret_deletion_status check (
        status in ('PENDIENTE', 'PROCESANDO', 'COMPLETADO')),
    constraint ck_verifactu_secret_deletion_attempts check (attempts >= 0),
    constraint ck_verifactu_secret_deletion_state check (
        (status = 'PENDIENTE'
            and next_attempt_at is not null
            and processing_owner is null
            and processing_lease_until is null
            and completed_at is null)
        or (status = 'PROCESANDO'
            and next_attempt_at is null
            and processing_owner is not null
            and processing_lease_until is not null
            and completed_at is null)
        or (status = 'COMPLETADO'
            and next_attempt_at is null
            and processing_owner is null
            and processing_lease_until is null
            and completed_at is not null)
    )
);

create index ix_verifactu_secret_deletion_claim
    on verifactu_secret_deletion_job(status, next_attempt_at, processing_lease_until, created_at)
    where status in ('PENDIENTE', 'PROCESANDO');

-- Vuelve a intentar eliminaciones que pudieron confirmarse en BD antes de existir
-- una cola durable. La ruta es la misma ruta determinista usada por el almacén local.
insert into verifactu_secret_deletion_job (
    id, company_id, certificate_id, secret_path, reason, status,
    attempts, next_attempt_at, created_at, version)
select gen_random_uuid(), certificate.empresa_id, certificate.id,
       certificate.empresa_id::text || '/' || certificate.id::text || '/private-key.dpapi',
       'MIGRATION_RECONCILIATION', 'PENDIENTE', 0, current_timestamp,
       coalesce(certificate.deleted_at, current_timestamp), 0
from certificado_verifactu certificate
where certificate.status = 'ELIMINADO'
on conflict (secret_path) do nothing;
