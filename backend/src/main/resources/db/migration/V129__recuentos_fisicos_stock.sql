create table recuento_stock (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    almacen_id uuid not null references almacen(id),
    estado varchar(16) not null,
    notas text,
    creado_por uuid not null references usuario(id),
    creado_en timestamptz not null,
    confirmado_por uuid references usuario(id),
    confirmado_en timestamptz,
    cancelado_por uuid references usuario(id),
    cancelado_en timestamptz,
    version bigint not null default 0,
    constraint recuento_stock_estado_ck check (estado in ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    constraint recuento_stock_fechas_ck check (
        (estado = 'DRAFT' and confirmado_en is null and cancelado_en is null)
        or (estado = 'CONFIRMED' and confirmado_por is not null and confirmado_en is not null and cancelado_en is null)
        or (estado = 'CANCELLED' and cancelado_por is not null and cancelado_en is not null and confirmado_en is null)
    )
);

create unique index recuento_stock_borrador_almacen_uq
    on recuento_stock (tienda_id, almacen_id) where estado = 'DRAFT';
create index recuento_stock_tienda_fecha_idx
    on recuento_stock (tienda_id, creado_en desc);

create table recuento_stock_linea (
    id uuid primary key,
    recuento_id uuid not null references recuento_stock(id) on delete cascade,
    producto_id uuid not null references producto(id),
    cantidad_esperada numeric(19,3) not null,
    cantidad_contada numeric(19,3) not null,
    diferencia_aplicada numeric(19,3),
    version bigint not null default 0,
    unique (recuento_id, producto_id),
    check (cantidad_contada >= 0)
);

alter table movimiento_stock add column recuento_stock_id uuid references recuento_stock(id);
create unique index movimiento_stock_recuento_producto_uq
    on movimiento_stock (recuento_stock_id, producto_id)
    where recuento_stock_id is not null;
