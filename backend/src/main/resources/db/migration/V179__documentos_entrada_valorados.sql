alter table entrada_almacen
    add column tipo_documento varchar(32) not null default 'ENTRADA_ALMACEN',
    add column numero_externo varchar(128),
    add column fuente_precio varchar(16) not null default 'PURCHASE',
    add column descuento_global numeric(5,2) not null default 0;

alter table entrada_almacen
    add constraint ck_entrada_almacen_tipo_documento
        check (tipo_documento in ('ENTRADA_ALMACEN', 'ALBARAN_ENTRADA', 'FACTURA_ENTRADA')),
    add constraint ck_entrada_almacen_fuente_precio
        check (fuente_precio in ('PURCHASE', 'SALE', 'MEMBER', 'WHOLESALE', 'OFFER')),
    add constraint ck_entrada_almacen_descuento_global
        check (descuento_global between 0 and 100);

alter table entrada_almacen_linea
    alter column cantidad type numeric(19,3) using cantidad::numeric,
    add column descuento numeric(5,2) not null default 0,
    add column precio_personalizado boolean not null default false;

alter table entrada_almacen_linea
    add constraint ck_entrada_almacen_linea_descuento
        check (descuento between 0 and 100);

create table entrada_almacen_albaran_origen (
    factura_id uuid not null references entrada_almacen(id) on delete cascade,
    albaran_id uuid not null references entrada_almacen(id),
    primary key (factura_id, albaran_id),
    unique (albaran_id),
    check (factura_id <> albaran_id)
);

create index ix_entrada_almacen_tienda_tipo_fecha
    on entrada_almacen(tienda_id, tipo_documento, fecha desc, id desc);
create index ix_entrada_almacen_proveedor
    on entrada_almacen(tienda_id, proveedor_id)
    where proveedor_id is not null;
create index ix_entrada_albaran_origen_factura
    on entrada_almacen_albaran_origen(factura_id);

alter table movimiento_stock drop constraint if exists movimiento_stock_tipo_check;
alter table movimiento_stock
    add check (tipo in ('AJUSTE', 'TRANSFERENCIA_SALIDA', 'TRANSFERENCIA_ENTRADA',
        'SALIDA_ALMACEN', 'ENTRADA_ALMACEN', 'ALBARAN_ENTRADA', 'FACTURA_ENTRADA',
        'ALBARAN_VENTA', 'ALBARAN_COMPRA', 'TICKET', 'FACTURA_VENTA',
        'FACTURA_COMPRA', 'ANULACION'));
