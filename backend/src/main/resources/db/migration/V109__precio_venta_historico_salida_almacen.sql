alter table salida_almacen_linea
    add column precio_unitario_venta numeric(19,2) not null default 0;

alter table salida_almacen_linea
    add check (precio_unitario_venta >= 0);

update salida_almacen_linea linea
set precio_unitario_venta = precio.importe
from producto_precio precio, salida_almacen salida
where salida.id = linea.salida_id
  and precio.producto_id = linea.producto_id
  and precio.tarifa = 'VENTA'
  and salida.estado = 'CONFIRMADA'
  and linea.precio_unitario_venta = 0
  and precio.importe > 0;

alter table salida_almacen_linea
    drop column precio_unitario_compra;
