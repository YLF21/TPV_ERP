-- Keep the previous 17 integer digits while admitting three price decimals.
-- Widen stored prices only: do not recalculate historical totals, taxes or payments.
alter table producto alter column precio_compra type numeric(20,3);
alter table producto_precio alter column importe type numeric(20,3);
alter table producto_precio_historial alter column importe type numeric(20,3);
alter table producto_proveedor alter column precio_compra_bruto type numeric(20,3);
alter table entrada_almacen_linea alter column precio_unitario_compra type numeric(20,3);
alter table salida_almacen_linea alter column precio_unitario_venta type numeric(20,3);
alter table documento_linea alter column precio_unitario type numeric(20,3);
alter table venta_linea_eliminada alter column precio_unitario type numeric(20,3);
alter table autorizacion_cambio_precio_venta alter column precio_unitario type numeric(20,3);
