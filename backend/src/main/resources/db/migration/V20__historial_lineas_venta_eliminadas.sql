create table venta_linea_eliminada (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    terminal_id uuid not null references terminal(id),
    usuario_id uuid not null references usuario(id),
    eliminado_en timestamptz not null,
    tipo varchar(16) not null,
    producto_id uuid not null references producto(id),
    codigo varchar(128) not null,
    nombre varchar(255) not null,
    cantidad integer not null,
    precio_unitario numeric(19,2) not null,
    total numeric(19,2) not null,
    check (tipo in ('LINEA', 'LISTA')),
    check (cantidad > 0)
);

create index idx_venta_linea_eliminada_tienda_fecha
    on venta_linea_eliminada(tienda_id, eliminado_en desc);
