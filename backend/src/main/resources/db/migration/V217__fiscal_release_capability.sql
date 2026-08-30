-- Release identity is appended to the existing fiscal software identity.
-- Existing rows predate the release manifest and are backfilled as the DUAL
-- laboratory contract used by V186-V216; their historical evidence is not
-- rewritten and unknown build metadata stays NULL.
alter table version_sistema_fiscal
    add column release_id varchar(128),
    add column artifact_hash varchar(64),
    add column commit_hash varchar(64),
    add column capacidad_producto varchar(32),
    add column esquema_version varchar(32),
    add column manifest_hash varchar(64);

-- V192 protects this table with a BEFORE UPDATE trigger. The controlled
-- migration window temporarily replaces that trigger only to fill metadata
-- for already immutable rows, then restores it before the migration commits.
drop trigger tr_version_sistema_fiscal_inmutable on version_sistema_fiscal;

update version_sistema_fiscal
   set release_id = 'LEGACY-' || upper(replace(id::text, '-', '')),
       capacidad_producto = 'DUAL',
       esquema_version = 'V216'
 where release_id is null
    or capacidad_producto is null
    or esquema_version is null;

create trigger tr_version_sistema_fiscal_inmutable
before update or delete on version_sistema_fiscal
for each row execute function impedir_mutacion_fiscal();

alter table version_sistema_fiscal
    alter column release_id set not null,
    alter column capacidad_producto set not null,
    alter column esquema_version set not null;

alter table version_sistema_fiscal
    add constraint ck_version_sistema_fiscal_capacidad
        check (capacidad_producto in ('VERIFACTU_ONLY', 'DUAL')),
    add constraint ck_version_sistema_fiscal_release_id
        check (char_length(trim(release_id)) > 0),
    add constraint ck_version_sistema_fiscal_esquema
        check (char_length(trim(esquema_version)) > 0),
    add constraint ck_version_sistema_fiscal_artifact_hash
        check (artifact_hash is null or artifact_hash ~ '^[0-9A-Fa-f]{64}$'),
    add constraint ck_version_sistema_fiscal_commit_hash
        check (commit_hash is null or commit_hash ~ '^[0-9A-Fa-f]{7,64}$'),
    add constraint ck_version_sistema_fiscal_manifest_hash
        check (manifest_hash is null or manifest_hash ~ '^[0-9A-Fa-f]{64}$');

create index ix_version_sistema_fiscal_release
    on version_sistema_fiscal(empresa_id, instalacion_id, release_id);

-- The runtime marker also records the release contract. Existing installations
-- are explicitly DUAL; a fresh empty database may be adopted by the release
-- initializer after the fiscal state check.
alter table fiscal_runtime_guard
    add column release_id varchar(128),
    add column capacidad_producto varchar(32),
    add column esquema_version varchar(32),
    add column manifest_hash varchar(64);

update fiscal_runtime_guard
   set release_id = coalesce(release_id, 'LEGACY-V216'),
       capacidad_producto = coalesce(capacidad_producto, 'DUAL'),
       esquema_version = coalesce(esquema_version, 'V216')
 where id = 1;

alter table fiscal_runtime_guard
    alter column release_id set not null,
    alter column capacidad_producto set not null,
    alter column esquema_version set not null,
    add constraint ck_fiscal_runtime_guard_capacidad
        check (capacidad_producto in ('VERIFACTU_ONLY', 'DUAL')),
    add constraint ck_fiscal_runtime_guard_esquema
        check (char_length(trim(esquema_version)) > 0),
    add constraint ck_fiscal_runtime_guard_manifest_hash
        check (manifest_hash is null or manifest_hash ~ '^[0-9A-Fa-f]{64}$');
