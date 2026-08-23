-- The operational copy was created by V181 in entrada_almacen. The old
-- documento rows are no longer part of the incoming-document model.
create temporary table legacy_documento_entrada_ids (
    documento_id uuid primary key
) on commit drop;

insert into legacy_documento_entrada_ids (documento_id)
select id
from documento
where tipo in ('ALBARAN_COMPRA', 'FACTURA_COMPRA', 'RECTIFICATIVA_COMPRA')
   or (tipo in ('ALBARAN_COMPRA', 'FACTURA_COMPRA') and estado = 'ANULADO');

-- Stock history now points to the operational input id copied by V181.
update movimiento_stock
set documento_id = null
where documento_id in (select documento_id from legacy_documento_entrada_ids);

-- This metadata belonged exclusively to the removed Excel product-import
-- endpoint. Its document rows are removed in the same transaction.
drop table if exists producto_importacion_excel_linea;

-- The operational-event table is append-only during normal application use.
-- This migration is the exceptional lifecycle operation that removes the
-- retired legacy documents and their dependent history in one transaction.
alter table documento_evento_operativo
    disable trigger documento_evento_operativo_append_only;

-- Remove direct and indirect references whose FK column identifies a
-- commercial document. Several historical extensions use different table
-- names but the same documento_id/document_id convention. A failed delete is
-- retried after dependent rows have been processed in the next pass.
do $$
declare
    relation_row record;
    pass integer;
begin
    for pass in 1..8 loop
        for relation_row in
            select distinct table_schema, table_name, column_name
            from information_schema.columns
            where table_schema = 'public'
              and column_name in (
                    'documento_id', 'document_id', 'documento_devolucion_id',
                    'documento_origen_id', 'origen_documento_id', 'ticket_id')
              and table_name not in ('documento', 'entrada_almacen', 'entrada_almacen_linea')
        loop
            begin
                execute format(
                    'delete from %I.%I where %I in (select documento_id from legacy_documento_entrada_ids)',
                    relation_row.table_schema,
                    relation_row.table_name,
                    relation_row.column_name);
            exception
                when foreign_key_violation then
                    null;
            end;
        end loop;
    end loop;
end $$;

alter table documento_evento_operativo
    enable trigger documento_evento_operativo_append_only;

delete from documento
where id in (select documento_id from legacy_documento_entrada_ids);
