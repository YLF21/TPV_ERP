create table ajuste_stock_historial (
    movimiento_id uuid primary key references movimiento_stock(id),
    tienda_id uuid not null references tienda(id),
    almacen_id uuid not null references almacen(id),
    producto_id uuid not null references producto(id),
    codigo varchar(255) not null,
    codigo_barras varchar(255),
    nombre varchar(255) not null,
    cantidad_anterior numeric(19,3) not null,
    cantidad_ajuste numeric(19,3) not null,
    cantidad_posterior numeric(19,3) not null,
    motivo text not null,
    creado_en timestamptz not null,
    constraint ck_ajuste_stock_historial_saldo check (cantidad_anterior + cantidad_ajuste = cantidad_posterior)
);
create index ix_ajuste_stock_historial_tienda_fecha
    on ajuste_stock_historial(tienda_id, creado_en desc, movimiento_id desc);
create index ix_ajuste_stock_historial_almacen_fecha
    on ajuste_stock_historial(tienda_id, almacen_id, creado_en desc);
