alter table traspaso_almacen
    add column fecha date,
    add column numero_externo varchar(120),
    add column origen_precio varchar(16) not null default 'PURCHASE',
    add column descuento_global numeric(5,2) not null default 0,
    add column subtotal numeric(40,2) not null default 0,
    add column revision_edicion bigint not null default 0;

update traspaso_almacen set fecha = (creado_en at time zone 'UTC')::date;
alter table traspaso_almacen alter column fecha set not null;
alter table traspaso_almacen
    add constraint ck_traspaso_origen_precio check (origen_precio in ('PURCHASE','SALE','MEMBER','WHOLESALE','OFFER')),
    add constraint ck_traspaso_descuento_global check (descuento_global between 0 and 100),
    add constraint ck_traspaso_subtotal check (subtotal >= 0 and subtotal <> 'NaN'::numeric);

alter table traspaso_almacen_linea
    drop constraint uq_traspaso_almacen_producto,
    add column posicion integer,
    add column precio_unitario numeric(20,3) not null default 0,
    add column descuento numeric(5,2) not null default 0,
    add column precio_personalizado boolean not null default false;

with ordered as (
    select id, row_number() over (partition by traspaso_id order by codigo, id) as position
    from traspaso_almacen_linea
)
update traspaso_almacen_linea line set posicion = ordered.position from ordered where line.id = ordered.id;
alter table traspaso_almacen_linea alter column posicion set not null;
alter table traspaso_almacen_linea
    add constraint uq_traspaso_almacen_posicion unique (traspaso_id, posicion),
    add constraint ck_traspaso_linea_posicion check (posicion > 0),
    add constraint ck_traspaso_linea_precio check (precio_unitario >= 0 and precio_unitario <> 'NaN'::numeric),
    add constraint ck_traspaso_linea_descuento check (descuento between 0 and 100);
