-- New event records retain the exact frozen software identity used to create them.
-- Existing events remain nullable and are intentionally left as legacy evidence.
alter table registro_evento_fiscal
    add column version_sistema_id uuid references version_sistema_fiscal(id);

create index ix_registro_evento_fiscal_system_version
    on registro_evento_fiscal(version_sistema_id);
