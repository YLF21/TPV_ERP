-- Secondary fiscal endpoint indexes. V211/V212 remain unchanged because they
-- are owned by the cursor/export workstreams. These indexes support the
-- integrity keyset and the admin submission join without touching fiscal data.
-- The adjacent .sql.conf disables Flyway's transaction wrapper before using
-- CONCURRENTLY. Each index is dropped first so a retry also repairs an INVALID
-- index left behind by an interrupted non-transactional migration.

drop index concurrently if exists ix_registro_fiscal_integrity_scope_seq;
create index concurrently ix_registro_fiscal_integrity_scope_seq
    on registro_fiscal(empresa_id, instalacion_id, secuencia asc, id asc);

drop index concurrently if exists ix_registro_fiscal_submission_scope_id;
create index concurrently ix_registro_fiscal_submission_scope_id
    on registro_fiscal(empresa_id, tienda_id, instalacion_id, id);
