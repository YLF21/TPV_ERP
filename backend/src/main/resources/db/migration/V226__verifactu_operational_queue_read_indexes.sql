-- Indexes for the bounded VeriFactu administrative work set.
-- Historical fiscal records remain in the cursor-based history view. These
-- partial indexes cover only records/states that can still require an action.

drop index concurrently if exists ix_registro_fiscal_admin_active_scope_order;
create index concurrently ix_registro_fiscal_admin_active_scope_order
    on registro_fiscal(empresa_id, tienda_id, instalacion_id,
                       secuencia asc, id asc)
    where modo_fiscal = 'VERIFACTU';

drop index concurrently if exists ix_estado_envio_fiscal_admin_active_order;
create index concurrently ix_estado_envio_fiscal_admin_active_order
    on estado_envio_fiscal(estado, actualizado_en asc, registro_id)
    where estado in ('PENDIENTE', 'ENVIANDO', 'ENVIADO', 'RECHAZADO');
