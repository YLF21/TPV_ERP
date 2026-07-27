alter table entrada_almacen_linea
    add column precio_unitario_compra numeric(19,2) not null default 0;

alter table salida_almacen_linea
    add column precio_unitario_compra numeric(19,2) not null default 0;

alter table entrada_almacen_linea
    add check (precio_unitario_compra >= 0);

alter table salida_almacen_linea
    add check (precio_unitario_compra >= 0);
