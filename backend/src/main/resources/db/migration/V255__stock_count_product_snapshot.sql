alter table recuento_stock_linea add column codigo_producto varchar(128);
alter table recuento_stock_linea add column codigo_barras varchar(128);
alter table recuento_stock_linea add column nombre_producto varchar(255);

update recuento_stock_linea line
set codigo_producto = (select identifier.valor from producto_identificador identifier
                       where identifier.producto_id = product.id and identifier.tipo = 'CODIGO'),
    codigo_barras = (select identifier.valor from producto_identificador identifier
                     where identifier.producto_id = product.id and identifier.tipo = 'CODIGO_BARRAS'),
    nombre_producto = product.nombre
from producto product
where product.id = line.producto_id;
