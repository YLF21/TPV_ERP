-- A removed refund line retains its signed quantity and total in the deletion audit.
-- Existing audit rows and commercial documents are not recalculated.
alter table venta_linea_eliminada
    drop constraint venta_linea_eliminada_cantidad_check;

alter table venta_linea_eliminada
    add constraint venta_linea_eliminada_cantidad_check check (cantidad <> 0);
