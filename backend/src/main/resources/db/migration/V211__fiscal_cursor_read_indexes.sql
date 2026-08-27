-- Supporting indexes for keyset fiscal-record navigation and scoped filters.
-- This migration is non-transactional through the adjacent Flyway .sql.conf file so
-- PostgreSQL can build them concurrently without blocking fiscal writes.

drop index concurrently if exists ix_registro_fiscal_cursor_scope_seq;
create index concurrently ix_registro_fiscal_cursor_scope_seq
    on registro_fiscal(empresa_id, tienda_id, instalacion_id, secuencia desc, id desc);

drop index concurrently if exists ix_registro_fiscal_cursor_issue_date;
create index concurrently ix_registro_fiscal_cursor_issue_date
    on registro_fiscal(empresa_id, tienda_id, instalacion_id, fecha_expedicion desc,
                       secuencia desc, id desc);

drop index concurrently if exists ix_registro_fiscal_cursor_number_prefix;
create index concurrently ix_registro_fiscal_cursor_number_prefix
    on registro_fiscal(
        empresa_id, tienda_id, instalacion_id,
        lower(serie_numero) text_pattern_ops,
        secuencia desc, id desc);

drop index concurrently if exists ix_registro_fiscal_cursor_number_exact;
create index concurrently ix_registro_fiscal_cursor_number_exact
    on registro_fiscal(
        empresa_id, tienda_id, instalacion_id,
        lower(serie_numero));

drop index concurrently if exists ix_registro_fiscal_relation_record;
create index concurrently ix_registro_fiscal_relation_record
    on registro_fiscal_relacion(registro_id, relacionado_id, tipo);

drop index concurrently if exists ix_estado_envio_fiscal_status_updated;
create index concurrently ix_estado_envio_fiscal_status_updated
    on estado_envio_fiscal(estado, actualizado_en desc, registro_id);
