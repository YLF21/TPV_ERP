alter table producto
    add column activo boolean not null default true;

alter table configuracion_stock
    add column permitir_venta_producto_desactivado boolean not null default false;
