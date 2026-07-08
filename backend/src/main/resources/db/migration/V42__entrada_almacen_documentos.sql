create table entrada_almacen (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    almacen_id uuid not null references almacen(id),
    proveedor_id uuid references proveedor(id),
    numero varchar(32),
    fecha date not null,
    estado varchar(16) not null default 'BORRADOR',
    origen varchar(255),
    concepto text,
    creada_por uuid not null references usuario(id),
    confirmada_por uuid references usuario(id),
    confirmada_en timestamptz,
    version bigint not null default 0,
    unique (tienda_id, numero),
    check (estado in ('BORRADOR', 'CONFIRMADA')),
    check ((estado = 'BORRADOR' and numero is null and confirmada_en is null)
        or (estado = 'CONFIRMADA' and numero is not null and confirmada_en is not null))
);

create table entrada_almacen_linea (
    id uuid primary key,
    entrada_id uuid not null references entrada_almacen(id) on delete cascade,
    producto_id uuid not null references producto(id),
    cantidad integer not null,
    version bigint not null default 0,
    check (cantidad > 0)
);

alter table movimiento_stock
    add column entrada_almacen_id uuid references entrada_almacen(id);

alter table movimiento_stock
    drop constraint if exists movimiento_stock_tipo_check;

alter table movimiento_stock
    add check (tipo in ('AJUSTE', 'TRANSFERENCIA_SALIDA', 'TRANSFERENCIA_ENTRADA',
        'SALIDA_ALMACEN', 'ENTRADA_ALMACEN', 'ALBARAN_VENTA', 'ALBARAN_COMPRA',
        'TICKET', 'FACTURA_VENTA', 'FACTURA_COMPRA', 'ANULACION'));

create index ix_entrada_almacen_tienda_fecha on entrada_almacen(tienda_id, fecha desc);
create index ix_entrada_almacen_linea_entrada on entrada_almacen_linea(entrada_id);
create index ix_movimiento_stock_entrada_almacen on movimiento_stock(entrada_almacen_id);
