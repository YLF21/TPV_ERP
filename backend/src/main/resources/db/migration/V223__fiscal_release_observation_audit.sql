-- Release/build observation is separate from fiscal evidence. It records the
-- runtime marker seen at startup and keeps the history append-only.
alter table fiscal_runtime_guard
    add column artifact_hash varchar(64),
    add column commit_hash varchar(64);

alter table fiscal_runtime_guard
    add constraint ck_fiscal_runtime_guard_artifact_hash
        check (artifact_hash is null or artifact_hash ~ '^[0-9A-Fa-f]{64}$'),
    add constraint ck_fiscal_runtime_guard_commit_hash
        check (commit_hash is null or commit_hash ~ '^[0-9A-Fa-f]{7,64}$');

create table fiscal_runtime_release_audit (
    id uuid primary key,
    runtime_class varchar(16) not null,
    capacidad_producto varchar(32) not null,
    release_id varchar(128) not null,
    esquema_version varchar(32) not null,
    manifest_hash varchar(64),
    artifact_hash varchar(64),
    commit_hash varchar(64),
    observado_en timestamptz not null,
    check (runtime_class in ('SANDBOX', 'REAL')),
    check (capacidad_producto in ('VERIFACTU_ONLY', 'DUAL')),
    check (manifest_hash is null or manifest_hash ~ '^[0-9A-Fa-f]{64}$'),
    check (artifact_hash is null or artifact_hash ~ '^[0-9A-Fa-f]{64}$'),
    check (commit_hash is null or commit_hash ~ '^[0-9A-Fa-f]{7,64}$')
);

create trigger tr_fiscal_runtime_release_audit_inmutable
before update or delete on fiscal_runtime_release_audit
for each row execute function impedir_mutacion_fiscal();

create index ix_fiscal_runtime_release_audit_observado
    on fiscal_runtime_release_audit(observado_en desc, id desc);
