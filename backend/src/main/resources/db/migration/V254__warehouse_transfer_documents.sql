create table traspaso_almacen (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    almacen_origen_id uuid not null references almacen(id),
    almacen_destino_id uuid not null references almacen(id),
    numero varchar(40) unique,
    estado varchar(16) not null,
    notas text,
    creado_por uuid not null references usuario(id),
    creado_en timestamptz not null,
    confirmado_por uuid references usuario(id),
    confirmado_en timestamptz,
    version bigint not null default 0,
    constraint ck_traspaso_almacen_distinto check (almacen_origen_id <> almacen_destino_id),
    constraint ck_traspaso_almacen_estado check (estado in ('DRAFT', 'CONFIRMED', 'CANCELLED'))
);
create index ix_traspaso_almacen_tienda_fecha on traspaso_almacen(tienda_id, creado_en desc, id desc);

create table traspaso_almacen_linea (
    id uuid primary key,
    traspaso_id uuid not null references traspaso_almacen(id) on delete cascade,
    producto_id uuid not null references producto(id),
    codigo varchar(255) not null,
    codigo_barras varchar(255),
    nombre varchar(255) not null,
    cantidad numeric(19,3) not null,
    constraint uq_traspaso_almacen_producto unique (traspaso_id, producto_id),
    constraint ck_traspaso_almacen_linea_cantidad check (cantidad > 0)
);

alter table movimiento_stock add column traspaso_almacen_id uuid references traspaso_almacen(id);
create index ix_movimiento_stock_traspaso_almacen on movimiento_stock(traspaso_almacen_id)
    where traspaso_almacen_id is not null;
alter table contador_documento drop constraint if exists contador_documento_tipo_check;
alter table contador_documento add constraint contador_documento_tipo_check
    check (tipo in ('SAL', 'ENT', 'AE', 'FE', 'AV', 'AC', 'T', 'FV', 'FC', 'FRV', 'FRC', 'TRA'));
