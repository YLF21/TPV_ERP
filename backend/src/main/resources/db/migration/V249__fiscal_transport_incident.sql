-- Transport recovery metadata, not a mutation of immutable fiscal evidence.
-- The scope lease serializes incident opening, batch dispatch and closure.
alter table flujo_envio_fiscal_scope
    add column incidencia_desde timestamptz;

comment on column flujo_envio_fiscal_scope.incidencia_desde is
    'Inicio de incidencia tecnica de remision; se conserva hasta drenar pendientes del scope';
