-- Operational status is always scoped by company and installation. This
-- narrow index lets the status endpoint locate the fiscal records before the
-- state/attempt aggregates, without selecting fiscal XML or snapshots.
drop index concurrently if exists ix_registro_fiscal_operational_scope;
create index concurrently ix_registro_fiscal_operational_scope
    on registro_fiscal(empresa_id, instalacion_id, id);
