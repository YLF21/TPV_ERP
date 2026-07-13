alter table producto
    add constraint ux_producto_tienda_id unique (tienda_id, id);

alter table almacen
    add constraint ux_almacen_tienda_id unique (tienda_id, id);

create table configuracion_stock (
    tienda_id uuid primary key references tienda(id) on delete cascade,
    almacen_predeterminado_id uuid not null,
    permitir_stock_negativo boolean not null default true,
    stock_minimo_predeterminado numeric(19, 3) not null default 5.000,
    alertas_habilitadas boolean not null default true,
    version bigint not null default 0,
    constraint fk_configuracion_stock_almacen_tienda
        foreign key (tienda_id, almacen_predeterminado_id)
        references almacen(tienda_id, id),
    constraint ck_configuracion_stock_minimo_no_negativo
        check (stock_minimo_predeterminado >= 0)
);

insert into configuracion_stock (tienda_id, almacen_predeterminado_id)
select store.id, warehouse.id
from tienda store
join almacen warehouse
    on warehouse.tienda_id = store.id
   and warehouse.predeterminado;

create table stock_minimo_almacen (
    id uuid primary key,
    tienda_id uuid not null references tienda(id) on delete cascade,
    producto_id uuid not null,
    almacen_id uuid not null,
    cantidad_minima numeric(19, 3) not null,
    version bigint not null default 0,
    constraint ux_stock_minimo_producto_almacen unique (producto_id, almacen_id),
    constraint fk_stock_minimo_producto_tienda
        foreign key (tienda_id, producto_id)
        references producto(tienda_id, id) on delete cascade,
    constraint fk_stock_minimo_almacen_tienda
        foreign key (tienda_id, almacen_id)
        references almacen(tienda_id, id) on delete cascade,
    constraint ck_stock_minimo_almacen_no_negativo check (cantidad_minima >= 0)
);

create index ix_stock_minimo_almacen_tienda
    on stock_minimo_almacen(tienda_id);

create index ix_documento_linea_producto_documento
    on documento_linea(producto_id, documento_id);

create index ix_documento_tienda_almacen_fecha
    on documento(tienda_id, almacen_id, fecha desc);
