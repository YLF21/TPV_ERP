alter table ajuste_stock_historial alter column cantidad_anterior drop not null;
alter table ajuste_stock_historial alter column cantidad_posterior drop not null;

insert into ajuste_stock_historial (
    movimiento_id, tienda_id, almacen_id, producto_id, codigo, codigo_barras, nombre,
    cantidad_anterior, cantidad_ajuste, cantidad_posterior, motivo, creado_en
)
select movement.id, warehouse.tienda_id, movement.almacen_id, movement.producto_id,
       coalesce(code.valor, movement.producto_id::text), barcode.valor, product.nombre,
       null, movement.cantidad, null, coalesce(movement.motivo, 'Ajuste histórico'), movement.creado_en
from movimiento_stock movement
join almacen warehouse on warehouse.id = movement.almacen_id
join producto product on product.id = movement.producto_id
left join producto_identificador code on code.producto_id = product.id and code.tipo = 'CODIGO'
left join producto_identificador barcode on barcode.producto_id = product.id and barcode.tipo = 'CODIGO_BARRAS'
where movement.tipo = 'AJUSTE' and movement.recuento_stock_id is null
  and not exists (select 1 from ajuste_stock_historial history where history.movimiento_id = movement.id);
