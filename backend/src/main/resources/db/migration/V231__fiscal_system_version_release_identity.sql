-- The public SIF version is not a release identity. Keep historical rows
-- untouched while allowing multiple immutable 4.2.0 identities, one per
-- release. The append-only trigger from V192 remains installed throughout.
alter table version_sistema_fiscal
    -- PostgreSQL 18 truncates the generated V186 name to this exact 63-byte
    -- identifier; keep the persisted name so the migration is runnable.
    drop constraint version_sistema_fiscal_empresa_id_instalacion_id_version_si_key;

alter table version_sistema_fiscal
    add constraint uq_version_sistema_fiscal_release
        unique (empresa_id, instalacion_id, version_sistema,
                numero_instalacion, release_id);
