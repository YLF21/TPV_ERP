update entrada_almacen_linea linea
set precio_unitario_compra = producto.precio_compra
from producto, entrada_almacen entrada
where entrada.id = linea.entrada_id
  and producto.id = linea.producto_id
  and entrada.estado = 'CONFIRMADA'
  and linea.precio_unitario_compra = 0
  and producto.precio_compra > 0;

update salida_almacen_linea linea
set precio_unitario_compra = producto.precio_compra
from producto, salida_almacen salida
where salida.id = linea.salida_id
  and producto.id = linea.producto_id
  and salida.estado = 'CONFIRMADA'
  and linea.precio_unitario_compra = 0
  and producto.precio_compra > 0;
