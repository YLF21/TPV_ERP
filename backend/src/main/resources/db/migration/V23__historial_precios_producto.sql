create table producto_precio_historial (
    id uuid primary key,
    producto_id uuid not null references producto(id) on delete cascade,
    tipo varchar(16) not null,
    importe numeric(19,2),
    actualizado_en timestamptz not null,
    version bigint not null default 0,
    check (tipo in ('COSTE', 'VENTA', 'MEMBER', 'MAYORISTA', 'OFERTA')),
    check (importe is null or importe >= 0)
);

create index ix_producto_precio_historial_producto_fecha
    on producto_precio_historial(producto_id, actualizado_en desc);
