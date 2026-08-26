-- Keyset index for APP GESTIÓN fiscal-event history.
-- The export and required-submission timestamp indexes already exist in V210.
-- Drop first so a retry also repairs an invalid index left by an interrupted build.
drop index concurrently if exists ix_registro_evento_fiscal_cursor_scope_seq;
create index concurrently ix_registro_evento_fiscal_cursor_scope_seq
    on registro_evento_fiscal(empresa_id, instalacion_id, secuencia desc, id desc);

drop index concurrently if exists ix_registro_evento_fiscal_summary_scope_time;
create index concurrently ix_registro_evento_fiscal_summary_scope_time
    on registro_evento_fiscal(empresa_id, instalacion_id, tipo_evento, generado_en desc);

drop index concurrently if exists ix_registro_fiscal_summary_scope_mode_time;
create index concurrently ix_registro_fiscal_summary_scope_mode_time
    on registro_fiscal(empresa_id, instalacion_id, modo_fiscal, generado_en asc);
